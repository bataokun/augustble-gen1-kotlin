package io.github.bataokun.augustble

import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** BLE identifiers of the August Smart Lock (1st generation). */
object AugustBle {
    val COMMAND_SERVICE_UUID: UUID = UUID.fromString("bd4ac610-0b45-11e3-8ffd-0800200c9a66")

    /** Session channel: write here. */
    val SESSION_WRITE_UUID: UUID = UUID.fromString("bd4ac611-0b45-11e3-8ffd-0800200c9a66")

    /** Session channel: indications arrive here. */
    val SESSION_READ_UUID: UUID = UUID.fromString("bd4ac612-0b45-11e3-8ffd-0800200c9a66")

    /** Secure channel: write here. */
    val SECURE_WRITE_UUID: UUID = UUID.fromString("bd4ac613-0b45-11e3-8ffd-0800200c9a66")

    /** Secure channel: indications arrive here. */
    val SECURE_READ_UUID: UUID = UUID.fromString("bd4ac614-0b45-11e3-8ffd-0800200c9a66")

    /** Standard Client Characteristic Configuration descriptor UUID. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** Result of [AugustLock.status]. */
/**
 * Lock state as reported by GetStatus(LOCK_STATE), using the LockState
 * value table (see PROTOCOL.md):
 * 0 Init, 1 Calibrating, 2 Unlocking, 3 Unlocked, 4 Locking, 5 Locked,
 * 6 PolDiscovery, 7 UnknownStaticPosition, 8 None, 9 Unlatching,
 * 10 Unlatched, 11 Integrated_Other_System, 12 SecureMode, …
 */
enum class LockStatus {
    LOCKED,
    UNLOCKED,
    LOCKING,
    UNLOCKING,
    UNLATCHING,
    UNLATCHED,

    /** The bolt stopped between the locked and unlocked extents (value 7,
     *  "UnknownStaticPosition") — e.g. the thumb-turn was moved by hand to
     *  a midway position. A real state, not a read failure. */
    UNKNOWN_STATIC_POSITION,
    INIT,
    CALIBRATING,
    POL_DISCOVERY,
    NONE,
    INTEGRATED_OTHER_SYSTEM,
    SECURE_MODE,
    UNKNOWN,

    ;

    companion object {
        /** Maps the uint32 lock-state value to a [LockStatus]. */
        fun fromLockStateValue(value: Int): LockStatus = when (value) {
            0 -> INIT
            1 -> CALIBRATING
            2 -> UNLOCKING
            3 -> UNLOCKED
            4 -> LOCKING
            5 -> LOCKED
            6 -> POL_DISCOVERY
            7 -> UNKNOWN_STATIC_POSITION
            8 -> NONE
            9 -> UNLATCHING
            10 -> UNLATCHED
            11 -> INTEGRATED_OTHER_SYSTEM
            12 -> SECURE_MODE
            else -> UNKNOWN
        }
    }
}

/** Door state as reported by GetStatus(DOOR_STATE / DOOR_LOCK_STATE). */
enum class DoorStatus {
    UNKNOWN,
    CLOSED,
    AJAR,
    OPEN;

    companion object {
        fun fromValue(value: Int): DoorStatus = when (value) {
            1 -> CLOSED
            2 -> AJAR
            3 -> OPEN
            else -> UNKNOWN
        }
    }
}

/** Battery state: pack [voltage] in volts and an estimated charge [percentage]. */
data class BatteryState(val voltage: Double, val percentage: Int)

/**
 * AA-cell discharge curve (per-cell volts → remaining percent), adopted
 * from the public yalexs-ble library (Apache License 2.0): returns the
 * percentage of the highest table voltage not exceeding [cellVolts]
 * (0 below the table).
 */
internal fun aaBatteryPercentage(cellVolts: Double): Int {
    val curve = listOf(
        1.24 to 0, 1.25 to 5, 1.26 to 10, 1.27 to 15, 1.28 to 20,
        1.29 to 25, 1.30 to 27, 1.31 to 28, 1.32 to 30, 1.33 to 35,
        1.34 to 40, 1.35 to 45, 1.36 to 50, 1.37 to 55, 1.38 to 60,
        1.39 to 65, 1.40 to 70, 1.45 to 75, 1.46 to 80, 1.471 to 85,
        1.49 to 90, 1.49075 to 93, 1.547 to 94, 1.548 to 95,
        1.549 to 97, 1.55 to 100,
    )
    var percentage = 0
    for ((volts, pct) in curve) {
        if (cellVolts >= volts) percentage = pct else break
    }
    return percentage
}

/**
 * The August Gen1 lock protocol, following the protocol model in
 * PROTOCOL.md (frame classes, response matching, GetStatus semantics).
 *
 * This class assumes the two [GattChannel]s are already connected, services
 * discovered and indications enabled; on Android, `io.github.bataokun.augustble.android`
 * provides that transport. Call [connect] first to run the secure handshake,
 * then [lock]/[unlock]/[status], and [disconnect] when done.
 *
 * @param offlineKey the 16-byte offline key recognised by the lock
 *   (32 hex characters).
 * @param offlineKeyOffset the key slot/offset paired with [offlineKey]; must be non-zero.
 * @param scope scope that owns the indication collectors; cancelled by the caller.
 * @param commandTimeoutMs per-command response timeout, added because a
 *   hung GATT operation on Android otherwise blocks forever.
 * @param handshakeRandom source of the 16 random handshake bytes
 *   (injectable for tests).
 */
class AugustLock(
    sessionChannel: GattChannel,
    secureChannel: GattChannel,
    private val offlineKey: ByteArray,
    private val offlineKeyOffset: Int,
    private val scope: CoroutineScope,
    private val commandTimeoutMs: Long = 10_000,
    private val handshakeRandom: () -> ByteArray = {
        ByteArray(16).also { SecureRandom().nextBytes(it) }
    },
    /** Optional sink for human-readable progress lines (on-device debugging). */
    val logger: ((String) -> Unit)? = null,
) {
    private val session = LockSession(sessionChannel)
    private val secureSession = SecureLockSession(secureChannel, offlineKeyOffset)

    /**
     * Asynchronous session-channel frames (lock events and other
     * non-response traffic), decrypted, in arrival order. The lock emits
     * several of these around every operation; they are NOT command
     * responses and are routed here instead of the response path.
     */
    val events: Flow<ByteArray> = session.events

    /** True between a successful [connect] handshake and [disconnect]. */
    var isSecure: Boolean = false
        private set

    /** The session key negotiated during [connect] (diagnostics/tests). */
    var sessionKey: ByteArray? = null
        private set

    init {
        require(offlineKey.size == 16) { "offlineKey must be 16 bytes, got ${offlineKey.size}" }
        require(offlineKeyOffset != 0) { "offlineKeyOffset must be specified (non-zero)" }
        require(commandTimeoutMs > 0) { "commandTimeoutMs must be positive, got $commandTimeoutMs" }
    }

    /**
     * Runs the secure handshake: key exchange + initialisation (the BLE
     * connection itself and characteristic discovery happen in the
     * transport layer beforehand).
     */
    suspend fun connect() {
        val handshakeKeys = handshakeRandom()
        require(handshakeKeys.size == 16) { "handshake random must be 16 bytes" }
        isSecure = false

        secureSession.setKey(offlineKey)
        secureSession.start(scope)
        session.start(scope)

        // SEC_LOCK_TO_MOBILE_KEY_EXCHANGE (0x01 -> 0x02)
        logger?.invoke("handshake: key exchange (SEC 0x01)")
        val exchangeCmd = secureSession.buildCommand(0x01)
        handshakeKeys.copyInto(exchangeCmd, 0x04, 0x00, 0x08)
        val exchangeResp = secureSession.execute(exchangeCmd, commandTimeoutMs)
        if (exchangeResp[0].toInt() != 0x02) {
            throw AugustProtocolException(
                "unexpected response to SEC_LOCK_TO_MOBILE_KEY_EXCHANGE: ${exchangeResp.toHexString()}"
            )
        }

        // From here a failure must not leave the lock claiming to be
        // secure (task 2.3): reset isSecure and rethrow, so callers retry
        // connect() instead of issuing commands into a half-open session.
        isSecure = true
        try {
            // Session key = our random[0..8) + lock's random (response bytes 4..12)
            val key = ByteArray(16)
            handshakeKeys.copyInto(key, 0x00, 0x00, 0x08)
            exchangeResp.copyInto(key, 0x08, 0x04, 0x0c)
            sessionKey = key
            session.setKey(key)
            secureSession.setKey(key)

            logger?.invoke("handshake: key exchange OK, session key established")
            // SEC_INITIALIZATION_COMMAND (0x03 -> 0x04)
            logger?.invoke("handshake: initialisation (SEC 0x03)")
            val initCmd = secureSession.buildCommand(0x03)
            handshakeKeys.copyInto(initCmd, 0x04, 0x08, 0x10)
            val initResp = secureSession.execute(initCmd, commandTimeoutMs)
            if (initResp[0].toInt() != 0x04) {
                throw AugustProtocolException(
                    "unexpected response to SEC_INITIALIZATION_COMMAND: ${initResp.toHexString()}"
                )
            }
        } catch (e: Exception) {
            isSecure = false
            throw e
        }
        logger?.invoke("handshake: complete")
    }

    /**
     * Force-lock (session opcode 0x0b). Waits for the operation response
     * (emitted when the motor stops) and returns it; throws
     * [AugustOperationException] if the lock reports a failure such as a
     * jam. See [LockSession.executeOperation].
     */
    suspend fun lock(): ByteArray {
        check(isSecure) { "call connect() before commands" }
        return session.executeOperation(0x0b, 0x00, commandTimeoutMs)
    }

    /** Force-unlock (session opcode 0x0a); see [lock]. */
    suspend fun unlock(): ByteArray {
        check(isSecure) { "call connect() before commands" }
        return session.executeOperation(0x0a, 0x00, commandTimeoutMs)
    }

    /**
     * Unlatch (session opcode 0x0a with operation byte 0x0A): retracts the
     * latch bolt momentarily, without driving the deadbolt.
     */
    suspend fun unlatch(): ByteArray {
        check(isSecure) { "call connect() before commands" }
        return session.executeOperation(0x0a, OPERATION_BYTE_UNLATCH, commandTimeoutMs)
    }

    /**
     * Secure-mode lock (session opcode 0x0b with operation byte 0x04):
     * locks and disables exterior access until an interior unlock.
     */
    suspend fun secureLock(): ByteArray {
        check(isSecure) { "call connect() before commands" }
        return session.executeOperation(0x0b, OPERATION_BYTE_SECURE_MODE, commandTimeoutMs)
    }

    /** Convenience aliases. */
    suspend fun forceLock(): ByteArray = lock()
    suspend fun forceUnlock(): ByteArray = unlock()

    /** Runs a GetStatus query for [statusIndex] and returns the raw response frame. */
    private suspend fun getStatusFrame(statusIndex: Int): ByteArray {
        val cmd = session.buildCommand(OPCODE_GET_STATUS)
        cmd[0x04] = statusIndex.toByte()
        return session.execute(cmd, commandTimeoutMs)
    }

    /**
     * The raw uint32 value (at byte 8) of a GetStatus response for any
     * status index — for probing indices without a typed accessor (e.g.
     * BATTERY_LEVEL, index 5).
     */
    suspend fun getStatusValue(statusIndex: Int): Long {
        check(isSecure) { "call connect() before commands" }
        return getStatusFrame(statusIndex).readUInt32LE(0x08)
    }

    /**
     * Lock status: a GetStatus command (magic 0xEE, opcode 0x02) whose
     * byte 4 carries the status index being queried — here LOCK_STATE
     * (index 2 in the status table). The matching Response (magic 0xBB,
     * opcode 0x02, byte 4 echoing the index) carries the state as a
     * uint32 LE at byte 8, interpreted with the LockState value table
     * (3 = Unlocked, 5 = Locked, …). Event frames the lock emits around
     * operations never reach this code — see [LockSessionBase] for the
     * routing.
     */
    suspend fun status(): LockStatus {
        check(isSecure) { "call connect() before commands" }
        return LockStatus.fromLockStateValue(getStatusValue(STATUS_INDEX_LOCK_STATE).toInt())
    }

    /** Door state (GetStatus index 46, DOOR_STATE; value at byte 8). */
    suspend fun doorStatus(): DoorStatus {
        check(isSecure) { "call connect() before commands" }
        return DoorStatus.fromValue(getStatusValue(STATUS_INDEX_DOOR_STATE).toInt())
    }

    /**
     * Combined lock + door state in one query (GetStatus index 47,
     * DOOR_LOCK_STATE): the lock state is at byte 8, the door state at
     * byte 9 of the response.
     */
    suspend fun lockAndDoorStatus(): Pair<LockStatus, DoorStatus> {
        check(isSecure) { "call connect() before commands" }
        val response = getStatusFrame(STATUS_INDEX_DOOR_LOCK_STATE)
        return LockStatus.fromLockStateValue(response[0x08].toInt() and 0xff) to
            DoorStatus.fromValue(response[0x09].toInt() and 0xff)
    }

    /**
     * Battery state (GetStatus index 0x0F): the response carries the pack
     * voltage as a uint16 in millivolts at byte 8; the percentage is
     * estimated per AA cell with the discharge curve adopted from the
     * public yalexs-ble library. Locks without door sense / battery
     * reporting may not answer this query.
     */
    suspend fun battery(): BatteryState {
        check(isSecure) { "call connect() before commands" }
        val response = getStatusFrame(STATUS_INDEX_BATTERY)
        val millivolts = (response[0x08].toInt() and 0xff) or
            ((response[0x09].toInt() and 0xff) shl 8)
        val voltage = millivolts / 1000.0
        return BatteryState(voltage, aaBatteryPercentage(voltage / 4.0))
    }

    /**
     * Sends the secure DISCONNECT command (0x05, expecting 0x8b) if a session
     * is established. Closing the underlying BLE connection afterwards is the
     * transport layer's job.
     */
    suspend fun disconnect() {
        if (!isSecure) {
            session.stop()
            secureSession.stop()
            return
        }
        try {
            val cmd = secureSession.buildCommand(0x05)
            cmd[0x11] = 0x00 // zero offline key for security terminate
            val response = secureSession.execute(cmd, commandTimeoutMs)
            if (response[0].toInt() and 0xff != 0x8b) {
                throw AugustProtocolException(
                    "unexpected response to DISCONNECT: ${response.toHexString()}"
                )
            }
        } finally {
            isSecure = false
            session.stop()
            secureSession.stop()
        }
    }
}
