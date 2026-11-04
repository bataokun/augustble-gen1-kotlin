package io.github.bataokun.augustble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 2 robustness tests, one per internal robustness task item:
 *
 *  - 2.1 concurrent commands are serialised by [LockSessionBase] (both
 *    routes hold the same mutex; the two routes stay separate);
 *  - 2.2 all public commands refuse to run before [AugustLock.connect];
 *  - 2.3 a handshake failure after "secure" resets [AugustLock.isSecure]
 *    and `commandTimeoutMs` must be positive.
 *
 * 2.4 (binder-callback hardening in `GattTransport`) lives in the Android
 * module, which has no unit test harness — it is validated only by the
 * on-device checklist (README).
 *
 * Fakes are copied from AugustLockUnitBehaviorTest / AugustLockEventRoutingTest
 * (which are themselves off-limits as fixtures of previously verified
 * behaviour); only additions are: an init-response opcode switch for 2.3
 * and the gated fake channel for 2.1.
 */
class AugustLockRobustnessTest {

    private val offlineKey = "00112233445566778899aabbccddeeff".hexToBytes()
    private val offset = 1
    private val lockRandom = ByteArray(8) { (0x50 + it).toByte() }
    private val sessionKeyForLockSession = "feedfacefeedfacefeedfacefeedface".hexToBytes()

    // ----------------------------------------------------------------
    // 2.1 — mutual exclusion of the two command routes
    // ----------------------------------------------------------------

    /**
     * A [GattChannel] fake that records every written frame's opcode and
     * holds its answer until the test releases [gate]. Responses are
     * Acknowledge frames (magic 0xAA) echoing the command's opcode — a
     * valid completion for any non-GetStatus command.
     */
    private inner class GateChannel : GattChannel {
        private val out = Channel<ByteArray>(Channel.UNLIMITED)
        override val incoming: Flow<ByteArray> = out.receiveAsFlow()
        private var mirror: StatefulCbcCipher? = null
        val writes = mutableListOf<Int>()
        private val gate = Channel<Unit>(Channel.UNLIMITED)

        fun mirrorKey(key: ByteArray) {
            mirror = StatefulCbcCipher(key)
        }

        /** Lets the fake answer the next written command. */
        fun releaseNext() {
            gate.trySend(Unit)
        }

        override suspend fun write(value: ByteArray) {
            val cipher = mirror ?: error("mirror key not set")
            val plain = value.copyOf()
            cipher.decryptBlock(value.copyOfRange(0, 16)).copyInto(plain, 0)
            writes += plain[1].toInt() and 0xff
            gate.receive() // the test controls when an answer flows
            val ack = ByteArray(FRAME_SIZE)
            ack[0] = 0xaa.toByte()
            ack[1] = plain[1]
            ack[4] = plain[4]
            ack[0x10] = 0x02
            var sum = 0
            for (i in 0..0x10) sum = (sum + (ack[i].toInt() and 0xff)) and 0xff
            ack[0x11] = (-sum).toByte()
            val wire = ack.copyOf()
            cipher.encryptBlock(ack.copyOfRange(0, 16)).copyInto(wire, 0)
            out.trySend(wire)
        }
    }

    @Test
    fun `concurrent executes cannot interleave`() = runBlocking {
        val channel = GateChannel()
        val session = LockSession(channel)
        session.setKey(sessionKeyForLockSession)
        channel.mirrorKey(sessionKeyForLockSession)
        session.start(CoroutineScope(Dispatchers.Unconfined))
        try {
            var first: ByteArray? = null
            var second: ByteArray? = null
            val job1 = launch(Dispatchers.Unconfined) {
                first = session.execute(session.buildCommand(0x0a), 5000)
            }
            assertEquals(listOf(0x0a), channel.writes)
            val job2 = launch(Dispatchers.Unconfined) {
                second = session.execute(session.buildCommand(0x0b), 5000)
            }
            assertEquals(
                listOf(0x0a), channel.writes,
                "the second command must not be written while the first is still in flight",
            )
            channel.releaseNext()
            assertEquals(
                listOf(0x0a, 0x0b), channel.writes,
                "the second write may only go out after the first command completed",
            )
            channel.releaseNext()
            job1.join()
            job2.join()
            assertEquals(0xaa, first!![0].toInt() and 0xff)
            assertEquals(0x0a, first!![1].toInt() and 0xff)
            assertEquals(0xaa, second!![0].toInt() and 0xff)
            assertEquals(0x0b, second!![1].toInt() and 0xff)
        } finally {
            session.stop()
        }
    }

    // ----------------------------------------------------------------
    // 2.2 — connect-guard on every public command
    // ----------------------------------------------------------------

    @Test
    fun `commands refuse to run before connect`() = runBlocking {
        val fake = FakeLock()
        val lock = newLock(fake, FakeSessionChannel(fake))
        val commands: List<Pair<String, suspend () -> Any?>> = listOf(
            "lock" to { lock.lock() },
            "unlock" to { lock.unlock() },
            "unlatch" to { lock.unlatch() },
            "secureLock" to { lock.secureLock() },
            "status" to { lock.status() },
            "doorStatus" to { lock.doorStatus() },
            "lockAndDoorStatus" to { lock.lockAndDoorStatus() },
            "battery" to { lock.battery() },
            "getStatusValue" to { lock.getStatusValue(STATUS_INDEX_LOCK_STATE) },
        )
        commands.forEach { (name, command) ->
            val ex = org.junit.jupiter.api.assertThrows<IllegalStateException> {
                runBlocking { command() }
            }
            assertEquals("call connect() before commands", ex.message, name)
        }
        // disconnect stays guard-free on purpose (Phase 1: it is a no-op pre-connect)
        lock.disconnect()
        assertFalse(lock.isSecure)
        assertEquals(0, fake.secWrites, "no guarded command may have reached the write path")
    }

    // ----------------------------------------------------------------
    // 2.3 — handshake init failure + timeout argument validation
    // ----------------------------------------------------------------

    @Test
    fun `a failed init step resets isSecure and connect can be retried`() = runBlocking {
        val fake = FakeLock()
        val session = FakeSessionChannel(fake)
        val lock = newLock(fake, session)

        fake.initRespondOpcode = 0x05 // not the expected 0x04
        val ex = org.junit.jupiter.api.assertThrows<AugustProtocolException> {
            runBlocking { lock.connect() }
        }
        assertTrue(ex.message!!.contains("SEC_INITIALIZATION_COMMAND"), ex.message)
        assertFalse(
            lock.isSecure,
            "a failed init step must not leave the lock claiming to be secure",
        )
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            runBlocking { lock.status() }
        }

        fake.initRespondOpcode = 0x04
        lock.connect()
        assertTrue(lock.isSecure, "the retry must re-establish the secure state")
        assertEquals(LockStatus.LOCKED, lock.status())
        lock.disconnect()
    }

    @Test
    fun `commandTimeoutMs must be positive`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            runBlocking {
                newLock(FakeLock(), FakeSessionChannel(FakeLock()), commandTimeoutMs = 0)
            }
        }.let { assertTrue(it.message!!.contains("commandTimeoutMs"), it.message) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            runBlocking {
                newLock(FakeLock(), FakeSessionChannel(FakeLock()), commandTimeoutMs = -1)
            }
        }.let { assertTrue(it.message!!.contains("commandTimeoutMs"), it.message) }
    }

    // ----------------------------------------------------------------
    // Fakes (copied from AugustLockUnitBehaviorTest; additions marked)
    // ----------------------------------------------------------------

    /** Shared state of the fake lock (both channels belong to one lock). */
    private inner class FakeLock {
        var secCipher = EcbBlockCipher(offlineKey)
        var sesCipher: StatefulCbcCipher? = null
        var lockState = 5 // Locked
        var doorState = 1 // Closed
        var batteryMillivolts = 5900
        var operationResult = 0
        var lastOperationByte: Int? = null
        var statusIndexSeen: Int? = null
        var secWrites = 0

        /** ADDITION for 2.3: what opcode the init step's answer carries. */
        var initRespondOpcode = 0x04

        fun secureFrame(opcode: Int): ByteArray {
            val frame = ByteArray(FRAME_SIZE)
            frame[0] = opcode.toByte()
            return frame
        }

        fun finishSecure(frame: ByteArray) {
            frame.writeUInt32LE(0x0c, Checksums.security(frame))
        }

        fun sessionFrame(magic: Int, opcode: Int): ByteArray {
            val frame = ByteArray(FRAME_SIZE)
            frame[0] = magic.toByte()
            frame[1] = opcode.toByte()
            frame[0x10] = 0x02
            fixTrailer(frame)
            return frame
        }

        fun fixTrailer(frame: ByteArray) {
            var sum = 0
            for (i in 0..0x10) sum = (sum + (frame[i].toInt() and 0xff)) and 0xff
            frame[0x11] = (-sum).toByte()
        }

        fun encryptSession(frame: ByteArray): ByteArray {
            val cipher = sesCipher ?: error("session cipher not established")
            val out = frame.copyOf()
            cipher.encryptBlock(frame.copyOfRange(0, 16)).copyInto(out, 0)
            return out
        }

        fun decryptSession(wire: ByteArray): ByteArray {
            val cipher = sesCipher ?: error("session cipher not established")
            val out = wire.copyOf()
            cipher.decryptBlock(wire.copyOfRange(0, 16)).copyInto(out, 0)
            return out
        }
    }

    private inner class FakeSecureChannel(private val fake: FakeLock) : GattChannel {
        private val out = Channel<ByteArray>(Channel.UNLIMITED)
        override val incoming: Flow<ByteArray> = out.receiveAsFlow()

        override suspend fun write(value: ByteArray) {
            val plain = decryptSecure(value)
            fake.secWrites++
            when (plain[0].toInt() and 0xff) {
                0x01 -> {
                    val resp = fake.secureFrame(0x02)
                    lockRandom.copyInto(resp, 0x04)
                    fake.finishSecure(resp)
                    val wire = resp.copyOf()
                    fake.secCipher.encryptBlock(resp.copyOfRange(0, 16)).copyInto(wire, 0)
                    out.trySend(wire)
                    val sessionKey = ByteArray(16)
                    plain.copyInto(sessionKey, 0, 0x04, 0x0c)
                    lockRandom.copyInto(sessionKey, 8)
                    fake.secCipher = EcbBlockCipher(sessionKey)
                    fake.sesCipher = StatefulCbcCipher(sessionKey)
                }
                0x03 -> respond(fake.initRespondOpcode) // ADDITION for 2.3
                0x05 -> {
                    respond(0x8b)
                    fake.secCipher = EcbBlockCipher(offlineKey)
                    fake.sesCipher = null
                }
                else -> error("unexpected SEC opcode ${plain[0]}")
            }
        }

        /** Re-handshake tolerance: fall back to the key-slot key (see UnitBehaviorTest). */
        private fun decryptSecure(wire: ByteArray): ByteArray {
            val plain = wire.copyOf()
            fake.secCipher.decryptBlock(wire.copyOfRange(0, 16)).copyInto(plain, 0)
            if ((plain[0].toInt() and 0xff) !in SECURE_OPCODES) {
                fake.secCipher = EcbBlockCipher(offlineKey)
                plain.fill(0)
                fake.secCipher.decryptBlock(wire.copyOfRange(0, 16)).copyInto(plain, 0)
                if ((plain[0].toInt() and 0xff) !in SECURE_OPCODES) {
                    error("unexpected SEC frame: neither the current nor the key-slot key decrypts it")
                }
            }
            return plain
        }

        private fun respond(opcode: Int) {
            val resp = fake.secureFrame(opcode)
            fake.finishSecure(resp)
            val wire = resp.copyOf()
            fake.secCipher.encryptBlock(resp.copyOfRange(0, 16)).copyInto(wire, 0)
            out.trySend(wire)
        }
    }

    /** The secure opcodes the fake lock can receive. */
    private val SECURE_OPCODES = setOf(0x01, 0x03, 0x05)

    private inner class FakeSessionChannel(private val fake: FakeLock) : GattChannel {
        private val out = Channel<ByteArray>(Channel.UNLIMITED)
        override val incoming: Flow<ByteArray> = out.receiveAsFlow()

        private fun emit(frame: ByteArray) {
            out.trySend(fake.encryptSession(frame))
        }

        override suspend fun write(value: ByteArray) {
            val plain = fake.decryptSession(value)
            when (plain[1].toInt() and 0xff) {
                0x0a, 0x0b -> {
                    val opcode = plain[1].toInt() and 0xff
                    fake.lastOperationByte = plain[4].toInt() and 0xff
                    fake.lockState = if (opcode == 0x0a) 3 else 5
                    val ack = fake.sessionFrame(0xaa, opcode)
                    ack[4] = plain[4]
                    fake.fixTrailer(ack)
                    emit(ack)
                    val resp = fake.sessionFrame(0xbb, opcode)
                    resp[0x0f] = fake.operationResult.toByte()
                    fake.fixTrailer(resp)
                    emit(resp)
                }
                0x02 -> {
                    fake.statusIndexSeen = plain[4].toInt() and 0xff
                    val resp = fake.sessionFrame(0xbb, 0x02)
                    resp[4] = plain[4]
                    val value = when (fake.statusIndexSeen) {
                        STATUS_INDEX_LOCK_STATE -> fake.lockState.toLong()
                        STATUS_INDEX_DOOR_STATE -> fake.doorState.toLong()
                        STATUS_INDEX_DOOR_LOCK_STATE ->
                            fake.lockState.toLong() or (fake.doorState.toLong() shl 8)
                        STATUS_INDEX_BATTERY -> fake.batteryMillivolts.toLong()
                        else -> 0L
                    }
                    resp.writeUInt32LE(0x08, value)
                    fake.fixTrailer(resp)
                    emit(resp)
                }
                else -> error("unexpected session opcode ${plain[1]}")
            }
        }
    }

    private fun newLock(
        fake: FakeLock,
        session: FakeSessionChannel,
        commandTimeoutMs: Long = 10_000,
    ): AugustLock = AugustLock(
        sessionChannel = session,
        secureChannel = FakeSecureChannel(fake),
        offlineKey = offlineKey,
        offlineKeyOffset = offset,
        scope = CoroutineScope(Dispatchers.Unconfined),
        commandTimeoutMs = commandTimeoutMs,
        handshakeRandom = { ByteArray(16) { (0xa0 + it).toByte() } },
    )
}
