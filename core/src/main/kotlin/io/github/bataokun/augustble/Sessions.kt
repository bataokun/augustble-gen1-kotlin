package io.github.bataokun.augustble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** A protocol-level failure (bad checksum, unexpected opcode, timeout...). */
open class AugustException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Result code reported at byte 0x0F of a lock/unlock operation response:
 * 0x00 is success; any other value is a failure (0x1E–0x23 are the MECH_*
 * motor faults — a stalled motor / jam). The table is adopted from the
 * public yalexs-ble library (Apache License 2.0).
 */
enum class OperationError(val code: Int) {
    COMM_SUCCESS(0x00),
    PARAM_NOT_PAIRED(0x01),
    PARAM_NOT_READABLE(0x02),
    WRONG_KEY(0x03),
    KEYCODE_DISABLE(0x04),
    KEYCODE_INVALID_ACCESS(0x05),
    KEYCODE_EXISTING_KEY(0x06),
    KEYCODE_NOSPACE(0x07),
    KEYCODE_TIMEOUT(0x08),
    KEYCODE_DIS_ONETOUCH(0x09),
    RFID_EXISTING_ID(0x0a),
    FINGERPRINT_EXISTING_ID(0x0b),
    PALM_EXISTING_ID(0x0d),
    MECH_TIMEOUT(0x1e),
    MECH_POSITION(0x1f),
    MECH_MOTPOL(0x20),
    MECH_TIMEOUT_CAL(0x21),
    MECH_BACKOFF(0x22),
    MECH_HANDLE_NOT_LIFTED(0x23),
    EMPTY_LOG(0x28),
    READING_LOG(0x29),
    VBAT_LOW(0x32),
    OVERTEMP(0x33),
    MAG_READ(0x34),
    MAG_BAD_DATA(0x35),
    INVALID_OPERATION(0x37),
    NOT_AUTHORIZED(0x38),
    NO_BUFS(0x39),
    INVALID_MSG(0x3a),
    NACK(0x3b),
    UNKNOWN_ERROR(0x3c),
    UNINITIALIZED(0x3d),
    BUSY(0x3e),
    NOT_FOUND(0x3f),
    SSID_IS_5GHZ(0x40),
    DHCP_FAILURE(0x41),
    DNS_FAILURE(0x42),
    CAL_BAD_EXTENTS(0x43),
    CAL_BAD_ANGLE(0x44),
    KEYCODE_SLOT_IN_USE(0x45),
    EXT_TIMEOUT(0x46);

    /** True for the mechanical faults (stalled motor / jam). */
    val isMechanical: Boolean get() = code in 0x1e..0x23

    companion object {
        fun fromCode(code: Int): OperationError? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The lock reported a failed mechanical operation: the operation response
 * carried a non-zero result code (see [OperationError]). [response] is the
 * raw operation-response frame for diagnosis.
 */
class AugustOperationException(
    val opcode: Int,
    val resultCode: Int,
    val response: ByteArray,
) : AugustException(
    "lock operation 0x%02x failed with result 0x%02x (%s)".format(
        opcode,
        resultCode,
        OperationError.fromCode(resultCode)?.name ?: "unrecognised code",
    )
)

class AugustProtocolException(message: String) : AugustException(message)

class AugustTimeoutException(message: String, cause: Throwable? = null) : AugustException(message, cause)

/**
 * One BLE characteristic pair (write + indication source), abstracted so the
 * protocol core stays Android-free and unit-testable.
 *
 * Implementations must deliver indications in the order they arrive and must
 * tolerate [write] being called from a single coroutine at a time (the core
 * serialises commands itself).
 */
interface GattChannel {
    suspend fun write(value: ByteArray)

    /** Raw indication payloads, in arrival order. */
    val incoming: Flow<ByteArray>
}

/** Session-channel opcode of the GetStatus command (see PROTOCOL.md). */
internal const val OPCODE_GET_STATUS = 0x02

/** Status index of LOCK_STATE in the GetStatus status table. */
internal const val STATUS_INDEX_LOCK_STATE = 2

/**
 * Status index of the battery poll whose response carries the pack
 * voltage as a uint16 (millivolts) at byte 8 — the poll the public
 * yalexs-ble library uses for Yale/August locks.
 */
internal const val STATUS_INDEX_BATTERY = 0x0f

/** Status index of DOOR_STATE in the GetStatus status table. */
internal const val STATUS_INDEX_DOOR_STATE = 46

/** Status index of DOOR_LOCK_STATE (lock at byte 8, door at byte 9). */
internal const val STATUS_INDEX_DOOR_LOCK_STATE = 47

/** Operation byte (byte 4) that turns an Unlock command into an unlatch. */
internal const val OPERATION_BYTE_UNLATCH = 0x0a

/** Operation byte (byte 4) that turns a Lock command into a secure-mode lock. */
internal const val OPERATION_BYTE_SECURE_MODE = 0x04

/** Byte offset of the result code in a lock/unlock operation response. */
internal const val OPERATION_RESULT_BYTE = 0x0f

/**
 * Base class for the two August channels.
 *
 * A collector coroutine decrypts incoming frames **in arrival order** (order
 * matters: the session channel's CBC decryption is stateful) and routes them
 * by frame class: frames that can complete a command ([isResponseCandidate])
 * go to the response queue; everything else is an asynchronous event and is
 * surfaced on [events] instead. This split is essential — after a lock/unlock
 * operation the lock emits several event frames on the session channel, and
 * treating the oldest queued frame as "the response" silently misreads them.
 *
 * Because the collector runs from [start] onward, a response can never
 * arrive "before we started listening".
 */
abstract class LockSessionBase(protected val channel: GattChannel) {
    protected val responses = Channel<ByteArray>(Channel.UNLIMITED)
    protected val eventFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    /**
     * Serialises command traffic across the two command routes
     * ([LockSessionBase.execute] and [LockSession.executeOperation]);
     * they are deliberately *not* merged, but they must never interleave.
     * A second command entering while the first is in flight would either
     * drain the first command's response as "stale" or race it on the
     * response queue. (Task 2.1; declared protected because the operation
     * route lives in the [LockSession] subclass.)
     */
    protected val sequential = Mutex()

    /** Decrypted frames that are not command responses (lock events, etc.). */
    val events: SharedFlow<ByteArray> = eventFrames

    private var collectorJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (collectorJob != null) return
        collectorJob = scope.launch {
            channel.incoming.collect { raw ->
                // Frame-length admission gate (adopted from yalexs-ble):
                // a payload that is not exactly one 18-byte frame must
                // never reach the cipher — the session channel's CBC
                // decryption consumes ciphertext in 16-byte blocks, so a
                // partial block would stay buffered in the chain and
                // desynchronise every later frame on the connection.
                if (raw.size != FRAME_SIZE) return@collect
                val frame = raw.copyOf()
                decryptFrameInPlace(frame)
                if (isResponseCandidate(frame)) {
                    responses.trySend(frame)
                } else {
                    eventFrames.tryEmit(frame)
                }
            }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
    }

    /** Set/replace the channel key; fresh cipher state. */
    abstract fun setKey(key: ByteArray)

    abstract fun buildCommand(opcode: Int): ByteArray

    /** Encrypts bytes 0..15 of [frame] in place; bytes 16..17 stay plaintext. No-op before a key is set. */
    protected abstract fun encryptFrameInPlace(frame: ByteArray)

    /** Decrypts bytes 0..15 of [frame] in place; bytes 16..17 stay plaintext. No-op before a key is set. */
    protected abstract fun decryptFrameInPlace(frame: ByteArray)

    protected abstract fun writeChecksum(frame: ByteArray)
    protected abstract fun validateResponse(frame: ByteArray)

    /** Whether a decrypted frame belongs to the command/response class at all. */
    protected abstract fun isResponseCandidate(frame: ByteArray): Boolean

    /** Whether [frame] (a response candidate) is the response to [command]. */
    protected abstract fun matchesResponse(command: ByteArray, frame: ByteArray): Boolean

    /**
     * Writes [command] (checksum added, then encrypted) and returns the
     * decrypted, validated response frame.
     *
     * Response selection:
     * candidates queued before the write belong to earlier traffic and are
     * re-routed to [events]; after the write, candidates are consumed until
     * one [matchesResponse]; non-matching candidates (e.g. an operation
     * response that arrives after an ACK already completed its command) are
     * re-routed to [events] rather than dropped.
     *
     * Runs under [sequential]: concurrent callers queue up on the mutex so
     * their commands cannot interleave on the channel (task 2.1).
     */
    suspend fun execute(command: ByteArray, timeoutMs: Long): ByteArray = sequential.withLock {
        require(command.size == FRAME_SIZE) { "command must be $FRAME_SIZE bytes" }
        while (true) {
            val stale = responses.tryReceive().getOrNull() ?: break
            eventFrames.tryEmit(stale)
        }
        val plain = command.copyOf()
        writeChecksum(plain)
        val wire = plain.copyOf()
        encryptFrameInPlace(wire)
        channel.write(wire)
        val response = try {
            withTimeout(timeoutMs) {
                var frame = responses.receive()
                while (!matchesResponse(command, frame)) {
                    eventFrames.tryEmit(frame)
                    frame = responses.receive()
                }
                frame
            }
        } catch (e: TimeoutCancellationException) {
            throw AugustTimeoutException("timed out after ${timeoutMs}ms waiting for lock response", e)
        }
        validateResponse(response)
        return response
    }
}

/**
 * The normal session channel (characteristics …611/…612):
 * AES-128-CBC, stateful — see [StatefulCbcCipher].
 */
class LockSession(channel: GattChannel) : LockSessionBase(channel) {
    @Volatile
    private var cipher: StatefulCbcCipher? = null

    override fun setKey(key: ByteArray) {
        cipher = StatefulCbcCipher(key)
    }

    override fun buildCommand(opcode: Int): ByteArray {
        val cmd = ByteArray(FRAME_SIZE)
        cmd[0x00] = 0xee.toByte() // magic
        cmd[0x01] = opcode.toByte()
        cmd[0x10] = 0x02
        return cmd
    }

    override fun encryptFrameInPlace(frame: ByteArray) {
        val c = cipher ?: return
        val encrypted = c.encryptBlock(frame.copyOfRange(0, 16))
        encrypted.copyInto(frame, 0)
    }

    override fun decryptFrameInPlace(frame: ByteArray) {
        val c = cipher ?: return
        val decrypted = c.decryptBlock(frame.copyOfRange(0, 16))
        decrypted.copyInto(frame, 0)
    }

    override fun writeChecksum(frame: ByteArray) {
        frame[0x03] = Checksums.simple(frame).toByte()
    }

    override fun validateResponse(frame: ByteArray) {
        if (Checksums.simple(frame) != 0) {
            throw AugustProtocolException("simple checksum mismatch in session response")
        }
        val magic = frame[0].toInt() and 0xff
        if (magic != 0xbb && magic != 0xaa) {
            throw AugustProtocolException("unexpected magic in session response: 0x%02x".format(magic))
        }
    }

    /**
     * Frame classes: incoming session frames are "Response" (magic 0xBB,
     * payload parsed per opcode) or "Acknowledge" (magic 0xAA, no
     * payload). Anything else is an event.
     */
    override fun isResponseCandidate(frame: ByteArray): Boolean {
        val magic = frame[0].toInt() and 0xff
        return magic == 0xbb || magic == 0xaa
    }

    /**
     * The matching rules: a Response completes the command whose opcode it
     * echoes in byte 1 (for GetStatus it must also echo the queried status
     * index in byte 4). An Acknowledge completes any command except
     * GetStatus, which requires the full Response.
     */
    override fun matchesResponse(command: ByteArray, frame: ByteArray): Boolean {
        val magic = frame[0].toInt() and 0xff
        val opcode = command[1].toInt() and 0xff
        return when (magic) {
            0xaa -> opcode != OPCODE_GET_STATUS
            0xbb -> {
                if ((frame[1].toInt() and 0xff) != opcode) {
                    false
                } else if (opcode == OPCODE_GET_STATUS) {
                    (frame[4].toInt() and 0xff) == (command[4].toInt() and 0xff)
                } else {
                    true
                }
            }
            else -> false
        }
    }

    /** Builds a session command carrying an operation byte at byte 4. */
    fun buildOperationCommand(opcode: Int, operationByte: Int): ByteArray {
        val cmd = buildCommand(opcode)
        cmd[0x04] = operationByte.toByte()
        return cmd
    }

    /**
     * Runs a mechanical operation (lock / unlock / unlatch / secure-mode
     * lock) as a staged wait, the way the public yalexs-ble library models
     * it: the lock first answers with an Acknowledge (magic 0xAA echoing
     * the opcode and the operation byte), and only when the motor stops
     * does it emit the operation response (magic 0xBB echoing the opcode)
     * whose byte 0x0F carries the result code — 0 on success, otherwise
     * an [OperationError] (a stalled motor / jam is 0x1E–0x23).
     *
     * Other frames received meanwhile are re-routed to [events]. Returns
     * the operation response on success; throws [AugustOperationException]
     * on a reported failure and [AugustTimeoutException] if no operation
     * response arrives in time.
     *
     * Runs under [sequential], the same mutex [execute] holds, so an
     * operation and a GetStatus can never interleave on the channel
     * (task 2.1). The two routes stay separate by design.
     */
    suspend fun executeOperation(opcode: Int, operationByte: Int, timeoutMs: Long): ByteArray =
        sequential.withLock {
            val command = buildOperationCommand(opcode, operationByte)
            while (true) {
                val stale = responses.tryReceive().getOrNull() ?: break
                eventFrames.tryEmit(stale)
            }
            val plain = command.copyOf()
            writeChecksum(plain)
            val wire = plain.copyOf()
            encryptFrameInPlace(wire)
            channel.write(wire)
            var acknowledged = false
            val response = try {
                withTimeout(timeoutMs) {
                    var result: ByteArray? = null
                    while (result == null) {
                        val frame = responses.receive()
                        val magic = frame[0].toInt() and 0xff
                        val frameOpcode = frame[1].toInt() and 0xff
                        when {
                            magic == 0xaa && frameOpcode == opcode &&
                                (frame[4].toInt() and 0xff) == operationByte ->
                                acknowledged = true
                            magic == 0xbb && frameOpcode == opcode -> result = frame
                            else -> eventFrames.tryEmit(frame)
                        }
                    }
                    result!!
                }
            } catch (e: TimeoutCancellationException) {
                throw AugustTimeoutException(
                    "timed out after ${timeoutMs}ms waiting for operation response " +
                        "(opcode=0x%02x, acknowledged=%s)".format(opcode, acknowledged),
                    e,
                )
            }
            validateResponse(response)
            val resultCode = response[OPERATION_RESULT_BYTE].toInt() and 0xff
            if (resultCode != 0) {
                throw AugustOperationException(opcode, resultCode, response)
            }
            return@withLock response
        }
}

/**
 * The secure channel (characteristics …613/…614): AES-128-ECB plus the
 * 32-bit security checksum. Used for the key exchange, initialisation and
 * disconnect commands.
 */
class SecureLockSession(
    channel: GattChannel,
    private val offlineKeyOffset: Int,
) : LockSessionBase(channel) {
    @Volatile
    private var cipher: EcbBlockCipher? = null

    override fun setKey(key: ByteArray) {
        cipher = EcbBlockCipher(key)
    }

    override fun buildCommand(opcode: Int): ByteArray {
        val cmd = ByteArray(FRAME_SIZE)
        cmd[0x00] = opcode.toByte()
        cmd[0x10] = 0x0f
        cmd[0x11] = offlineKeyOffset.toByte()
        return cmd
    }

    override fun encryptFrameInPlace(frame: ByteArray) {
        val c = cipher ?: return
        val encrypted = c.encryptBlock(frame.copyOfRange(0, 16))
        encrypted.copyInto(frame, 0)
    }

    override fun decryptFrameInPlace(frame: ByteArray) {
        val c = cipher ?: return
        val decrypted = c.decryptBlock(frame.copyOfRange(0, 16))
        decrypted.copyInto(frame, 0)
    }

    override fun writeChecksum(frame: ByteArray) {
        frame.writeUInt32LE(0x0c, Checksums.security(frame))
    }

    override fun validateResponse(frame: ByteArray) {
        if (Checksums.security(frame) != frame.readUInt32LE(0x0c)) {
            throw AugustProtocolException("security checksum mismatch in secure response")
        }
    }

    /**
     * The secure channel carries no asynchronous traffic: every frame is a
     * response, and the security checksum (checked in [validateResponse]) is
     * the guard — the first frame after a write is that command's response.
     */
    override fun isResponseCandidate(frame: ByteArray): Boolean = true

    override fun matchesResponse(command: ByteArray, frame: ByteArray): Boolean = true
}
