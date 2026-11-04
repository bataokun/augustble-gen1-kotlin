package io.github.bataokun.augustble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end test against a fake lock that behaves like the REAL lock
 * (see PROTOCOL.md for the frame model):
 *
 *  - incoming session frames are classed by magic: 0xBB "Response"
 *    (payload per opcode), 0xAA "Acknowledge", anything else is an event;
 *  - the lock emits asynchronous event frames around operations — under a
 *    naive "the next frame is the response" model those frames are
 *    misread as responses and status() returns UNKNOWN;
 *  - GetStatus responses echo the queried status index at byte 4 and
 *    carry the value as a uint32 LE at byte 8.
 *  - lock/unlock are staged: an 0xAA acknowledgment first, then the 0xBB
 *    operation response (emitted when the motor stops) whose byte 0x0F
 *    carries the result code.
 */
class AugustLockEventRoutingTest {

    private val offlineKey = "00112233445566778899aabbccddeeff".hexToBytes()
    private val offset = 1
    private val lockRandom = ByteArray(8) { (0x50 + it).toByte() }

    /** Shared state of the fake lock (both channels belong to one lock). */
    private inner class FakeLock {
        var secCipher = EcbBlockCipher(offlineKey)
        var sesCipher: StatefulCbcCipher? = null
        var lockState = 5 // Locked
        var doorState = 1 // Closed
        var batteryMillivolts = 5900
        var operationResult = 0 // result code for the next op-response
        var lastOperationByte: Int? = null
        var statusIndexSeen: Int? = null

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

        /** Recomputes the incoming-frame checksum: byte 0x11 = -(sum of bytes 0..0x10). */
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
            val plain = value.copyOf()
            fake.secCipher.decryptBlock(value.copyOfRange(0, 16)).copyInto(plain, 0)
            when (plain[0].toInt() and 0xff) {
                0x01 -> {
                    val resp = fake.secureFrame(0x02)
                    lockRandom.copyInto(resp, 0x04)
                    fake.finishSecure(resp)
                    val wire = resp.copyOf()
                    fake.secCipher.encryptBlock(resp.copyOfRange(0, 16)).copyInto(wire, 0)
                    out.trySend(wire)
                    // Session key = phone random[0..8) + lock random[0..8)
                    val sessionKey = ByteArray(16)
                    plain.copyInto(sessionKey, 0, 0x04, 0x0c)
                    lockRandom.copyInto(sessionKey, 8)
                    fake.secCipher = EcbBlockCipher(sessionKey)
                    fake.sesCipher = StatefulCbcCipher(sessionKey)
                }
                0x03 -> respond(0x04)
                0x05 -> respond(0x8b)
                else -> error("unexpected SEC opcode ${plain[0]}")
            }
        }

        private fun respond(opcode: Int) {
            val resp = fake.secureFrame(opcode)
            fake.finishSecure(resp)
            val wire = resp.copyOf()
            fake.secCipher.encryptBlock(resp.copyOfRange(0, 16)).copyInto(wire, 0)
            out.trySend(wire)
        }
    }

    private inner class FakeSessionChannel(private val fake: FakeLock) : GattChannel {
        private val out = Channel<ByteArray>(Channel.UNLIMITED)
        override val incoming: Flow<ByteArray> = out.receiveAsFlow()

        private fun emit(frame: ByteArray) {
            out.trySend(fake.encryptSession(frame))
        }

        /** A lock-event style frame (Response class, opcode 0x00). */
        private fun eventFrame(): ByteArray {
            val frame = fake.sessionFrame(0xbb, 0x00)
            frame[0x08] = 0x00 // byte 8 is meaningless here — the old code read it as a status
            fake.fixTrailer(frame)
            return frame
        }

        /** Sends raw bytes on the session channel, bypassing encryption. */
        fun injectRaw(bytes: ByteArray) {
            out.trySend(bytes)
        }

        override suspend fun write(value: ByteArray) {
            val plain = fake.decryptSession(value)
            when (plain[1].toInt() and 0xff) {
                0x0a, 0x0b -> { // lock / unlock / unlatch / secure lock (staged)
                    val opcode = plain[1].toInt() and 0xff
                    fake.lastOperationByte = plain[4].toInt() and 0xff
                    fake.lockState = if (opcode == 0x0a) 3 else 5
                    if (opcode == 0x0b) emit(eventFrame()) // an event even arrives first
                    val ack = fake.sessionFrame(0xaa, opcode)
                    ack[4] = plain[4]
                    fake.fixTrailer(ack)
                    emit(ack)
                    val resp = fake.sessionFrame(0xbb, opcode)
                    resp[0x0f] = fake.operationResult.toByte()
                    fake.fixTrailer(resp)
                    emit(resp)
                    emit(eventFrame())
                    if (opcode == 0x0a) emit(eventFrame())
                }
                0x02 -> { // GetStatus
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

    @Test
    fun `status survives event frames interleaved with operations`() = runBlocking {
        val fake = FakeLock()
        val lock = AugustLock(
            sessionChannel = FakeSessionChannel(fake),
            secureChannel = FakeSecureChannel(fake),
            offlineKey = offlineKey,
            offlineKeyOffset = offset,
            scope = CoroutineScope(Dispatchers.Unconfined),
            handshakeRandom = { ByteArray(16) { (0xa0 + it).toByte() } },
        )
        val eventList = mutableListOf<ByteArray>()
        val collectJob = launch(Dispatchers.Unconfined) {
            lock.events.collect { eventList += it }
        }

        lock.connect()
        assertTrue(lock.isSecure)

        lock.unlock()
        assertEquals(
            LockStatus.UNLOCKED, lock.status(),
            "status after unlock must come from the GetStatus response, not a queued event frame",
        )
        assertEquals(STATUS_INDEX_LOCK_STATE, fake.statusIndexSeen)

        lock.lock()
        assertEquals(
            LockStatus.LOCKED, lock.status(),
            "status after lock must come from the GetStatus response, not a queued event frame",
        )

        lock.disconnect()
        collectJob.cancel()
        assertEquals(
            4, eventList.size,
            "all four event frames must be routed to events, none consumed as a response",
        )
        assertTrue(eventList.all { (it[1].toInt() and 0xff) == 0x00 })
    }

    private fun newLock(fake: FakeLock, session: FakeSessionChannel): AugustLock =
        AugustLock(
            sessionChannel = session,
            secureChannel = FakeSecureChannel(fake),
            offlineKey = offlineKey,
            offlineKeyOffset = offset,
            scope = CoroutineScope(Dispatchers.Unconfined),
            handshakeRandom = { ByteArray(16) { (0xa0 + it).toByte() } },
        )

    @Test
    fun `operation failure surfaces the operation error`() = runBlocking {
        val fake = FakeLock()
        val lock = newLock(fake, FakeSessionChannel(fake))
        lock.connect()
        fake.operationResult = 0x20 // MECH_MOTPOL — a stalled motor / jam
        val ex = org.junit.jupiter.api.assertThrows<AugustOperationException> {
            runBlocking { lock.lock() }
        }
        assertEquals(0x20, ex.resultCode)
        assertEquals(OperationError.MECH_MOTPOL, OperationError.fromCode(ex.resultCode))
        assertTrue(OperationError.fromCode(ex.resultCode)!!.isMechanical)
        lock.disconnect()
    }

    @Test
    fun `unlatch sends the unlatch operation byte`() = runBlocking {
        val fake = FakeLock()
        val lock = newLock(fake, FakeSessionChannel(fake))
        lock.connect()
        lock.unlatch()
        assertEquals(OPERATION_BYTE_UNLATCH, fake.lastOperationByte)
        lock.secureLock()
        assertEquals(OPERATION_BYTE_SECURE_MODE, fake.lastOperationByte)
        lock.disconnect()
    }

    @Test
    fun `door and battery status parse`() = runBlocking {
        val fake = FakeLock()
        val lock = newLock(fake, FakeSessionChannel(fake))
        lock.connect()
        assertEquals(DoorStatus.CLOSED, lock.doorStatus())
        assertEquals(LockStatus.LOCKED to DoorStatus.CLOSED, lock.lockAndDoorStatus())
        val battery = lock.battery()
        assertEquals(5.9, battery.voltage, 0.001)
        assertEquals(85, battery.percentage) // 5.9V pack / 4 = 1.475V per cell
        lock.disconnect()
    }

    @Test
    fun `short frames are dropped without desyncing the session`() = runBlocking {
        val fake = FakeLock()
        val sessionChannel = FakeSessionChannel(fake)
        val lock = newLock(fake, sessionChannel)
        lock.connect()
        // A truncated notification must never reach the CBC decryptor:
        // fed in, its partial block would poison the chain for every
        // later frame. The admission gate drops it instead.
        sessionChannel.injectRaw(ByteArray(10) { 0x55 })
        assertEquals(LockStatus.LOCKED, lock.status())
        lock.disconnect()
    }

    @Test
    fun `lock state values map to the value table`() {
        // Observed on real hardware: turning the bolt by hand
        // to a midway position makes the lock report value 7 — a genuine
        // state (UnknownStaticPosition), not a read failure.
        assertEquals(LockStatus.UNKNOWN_STATIC_POSITION, LockStatus.fromLockStateValue(7))
        assertEquals(LockStatus.LOCKED, LockStatus.fromLockStateValue(5))
        assertEquals(LockStatus.UNLOCKED, LockStatus.fromLockStateValue(3))
        assertEquals(LockStatus.UNKNOWN, LockStatus.fromLockStateValue(99))
    }
}
