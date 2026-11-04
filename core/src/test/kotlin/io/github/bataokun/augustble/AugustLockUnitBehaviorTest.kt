package io.github.bataokun.augustble

import java.util.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit-level behaviour tests for the pieces the existing tests only cover
 * incidentally ([AugustLockVectorTest] replays whole sessions,
 * [AugustLockEventRoutingTest] pins frame routing): the value tables, the
 * battery curve, the checksum invariants, the manual CBC cipher and the
 * hex helpers, plus the command timeout / lifecycle idempotency paths
 * driven by fakes copied from [AugustLockEventRoutingTest].
 *
 * Everything here runs without BLE or Android. The connection recipe of
 * `GattTransport` is deliberately untouched by this test file.
 */
class AugustLockUnitBehaviorTest {

    private val offlineKey = "00112233445566778899aabbccddeeff".hexToBytes()
    private val offset = 1
    private val lockRandom = ByteArray(8) { (0x50 + it).toByte() }

    /** The secure-channel opcodes the fake lock can receive. */
    private val SECURE_OPCODES = setOf(0x01, 0x03, 0x05)

    // ----------------------------------------------------------------
    // Value tables (see PROTOCOL.md — its tables are the authority)
    // ----------------------------------------------------------------

    @Test
    fun `lock state value table covers 0 to 12 and maps the rest to unknown`() {
        val expected = mapOf(
            0 to LockStatus.INIT,
            1 to LockStatus.CALIBRATING,
            2 to LockStatus.UNLOCKING,
            3 to LockStatus.UNLOCKED,
            4 to LockStatus.LOCKING,
            5 to LockStatus.LOCKED,
            6 to LockStatus.POL_DISCOVERY,
            7 to LockStatus.UNKNOWN_STATIC_POSITION,
            8 to LockStatus.NONE,
            9 to LockStatus.UNLATCHING,
            10 to LockStatus.UNLATCHED,
            11 to LockStatus.INTEGRATED_OTHER_SYSTEM,
            12 to LockStatus.SECURE_MODE,
        )
        expected.forEach { (value, status) ->
            assertEquals(status, LockStatus.fromLockStateValue(value), "lock state value $value")
        }
        // 13–16 are keypad-oriented states a Gen1 lock is not expected to
        // report; anything else is equally unknown.
        assertEquals(LockStatus.UNKNOWN, LockStatus.fromLockStateValue(13))
        assertEquals(LockStatus.UNKNOWN, LockStatus.fromLockStateValue(16))
        assertEquals(LockStatus.UNKNOWN, LockStatus.fromLockStateValue(99))
        assertEquals(LockStatus.UNKNOWN, LockStatus.fromLockStateValue(-1))
    }

    @Test
    fun `door state value table`() {
        assertEquals(DoorStatus.CLOSED, DoorStatus.fromValue(1))
        assertEquals(DoorStatus.AJAR, DoorStatus.fromValue(2))
        assertEquals(DoorStatus.OPEN, DoorStatus.fromValue(3))
        assertEquals(DoorStatus.UNKNOWN, DoorStatus.fromValue(0))
        assertEquals(DoorStatus.UNKNOWN, DoorStatus.fromValue(99))
    }

    @Test
    fun `aa battery curve boundaries`() {
        // Curve entries are "the percentage of the highest table voltage
        // not exceeding the cell voltage; 0 below the table".
        assertEquals(0, aaBatteryPercentage(1.10), "below the table must read 0")
        assertEquals(0, aaBatteryPercentage(1.24), "1.24 is the 0% curve entry")
        assertEquals(0, aaBatteryPercentage(1.245), "between 1.24 and 1.25 is still 0%")
        assertEquals(70, aaBatteryPercentage(1.40), "1.40 is the 70% curve entry")
        assertEquals(75, aaBatteryPercentage(1.45), "1.45 is the 75% curve entry")
        assertEquals(100, aaBatteryPercentage(1.55), "1.55 is the full-charge entry")
    }

    // ----------------------------------------------------------------
    // Checksum invariants (PROTOCOL.md is the authority)
    // ----------------------------------------------------------------

    @Test
    fun `simple checksum balances both documented placements`() {
        // A valid frame sums to 0 mod 256. The lock-to-phone checksum
        // lives at byte 0x11, the phone-to-lock one at byte 0x03 — each
        // holds this same negated-total value (each placement is computed
        // while its own slot still reads zero).
        val rng = Random(0x5eed)
        repeat(20) {
            val frame = ByteArray(FRAME_SIZE).also { rng.nextBytes(it) }
            frame[0x03] = 0
            frame[0x11] = 0
            val checksum = Checksums.simple(frame).toByte()
            val written = frame.copyOf().also { it[0x03] = checksum }
            assertEquals(0, Checksums.simple(written), "byte 0x03 placement must balance the frame")
            val received = frame.copyOf().also { it[0x11] = checksum }
            assertEquals(0, Checksums.simple(received), "byte 0x11 placement must balance the frame")
        }
    }

    @Test
    fun `security checksum makes the first words sum to zero`() {
        // security() = -(w0 + w1 + w2) over the first three little-endian
        // uint32 words, stored at byte 0x0c (the fourth word). Afterwards
        // the recomputed checksum equals the stored one (what
        // SecureLockSession.validateResponse checks) and the four words
        // sum to zero mod 2^32.
        val rng = Random(0x5eed)
        repeat(20) {
            val frame = ByteArray(FRAME_SIZE).also { rng.nextBytes(it) }
            frame.writeUInt32LE(0x0c, Checksums.security(frame))
            assertEquals(
                Checksums.security(frame),
                frame.readUInt32LE(0x0c),
                "stored security checksum must equal the recomputed one",
            )
            val words = (0..3).map { frame.readUInt32LE(it * 4) }
            assertEquals(
                0L,
                words.sum() and 0xFFFF_FFFFL,
                "w0 + w1 + w2 + w3 must vanish mod 2^32",
            )
        }
    }

    // ----------------------------------------------------------------
    // Manual CBC cipher
    // ----------------------------------------------------------------

    @Test
    fun `stateful cbc carries the chain so identical plaintext encrypts differently`() {
        // Modelling the protocol: encrypt frames with one cipher (the
        // phone), then decrypt the stream in order with a second instance
        // over the same key (the lock's mirror, fresh chains each side).
        val enc = StatefulCbcCipher(offlineKey)
        val plain1 = ByteArray(16) { (it * 0x11).toByte() }
        val plain2 = ByteArray(16) { (0xff - it).toByte() }

        val cipher1 = enc.encryptBlock(plain1)
        val again1 = enc.encryptBlock(plain1)
        assertFalse(
            cipher1.contentEquals(again1),
            "the same plaintext twice must not repeat: the CBC chain carries across frames",
        )

        val mirror = StatefulCbcCipher(offlineKey)
        assertTrue(
            plain1.contentEquals(mirror.decryptBlock(cipher1)),
            "decrypting an encrypt output must restore the plaintext",
        )
        assertTrue(
            plain1.contentEquals(mirror.decryptBlock(again1)),
            "the second frame decrypts through the carried chain (a per-frame zero-IV decrypt would not)",
        )
        assertTrue(
            plain2.contentEquals(mirror.decryptBlock(enc.encryptBlock(plain2))),
            "the chain keeps carrying: a third frame still restores its plaintext",
        )
    }

    @Test
    fun `setKey resets both cipher chains`() {
        val cipher = StatefulCbcCipher(offlineKey)
        val plain = ByteArray(16) { (it * 3 + 5).toByte() }
        val first = cipher.encryptBlock(plain)
        cipher.encryptBlock(plain) // advance the chain in both directions

        cipher.setKey(offlineKey)
        val again = cipher.encryptBlock(plain)
        assertTrue(
            first.contentEquals(again),
            "setKey must zero the encrypt chain: the first block after reset reproduces the original",
        )
        assertTrue(
            plain.contentEquals(cipher.decryptBlock(first)),
            "setKey must zero the decrypt chain: a fresh decrypt of the first block works",
        )
    }

    @Test
    fun `decrypt chain is per-frame state too`() {
        val plain = ByteArray(16) { (it + 1).toByte() }
        val cipherText = StatefulCbcCipher(offlineKey).encryptBlock(plain)
        val cipher = StatefulCbcCipher(offlineKey)
        assertTrue(plain.contentEquals(cipher.decryptBlock(cipherText)))
        assertFalse(
            plain.contentEquals(cipher.decryptBlock(cipherText)),
            "decrypting the same block twice must differ — the decrypt chain advanced",
        )
    }

    // ----------------------------------------------------------------
    // Hex helpers
    // ----------------------------------------------------------------

    @Test
    fun `hex helpers round-trip and parse separators`() {
        val keyHex = "00112233445566778899aabbccddeeff"
        assertEquals(keyHex, keyHex.hexToBytes().toHexString(), "round-trip via lowercase hex")

        val separated = "AA:BB:CC:DD:EE:FF:10:20".hexToBytes()
        assertEquals(8, separated.size, "separators must be stripped before parsing")
        assertEquals("aabbccddeeff1020", separated.toHexString())
        assertEquals(-86, separated[0].toInt(), "0xAA parses to its signed byte value")

        val odd = org.junit.jupiter.api.assertThrows<IllegalArgumentException> { "abc".hexToBytes() }
        assertEquals("hex string must have an even number of digits", odd.message)
    }

    // ----------------------------------------------------------------
    // Operation result codes
    // ----------------------------------------------------------------

    @Test
    fun `unknown operation result codes map to null and say so in the message`() {
        assertNull(OperationError.fromCode(0xff), "an untabled code must not invent an enum value")
        val ex = AugustOperationException(0x0b, 0xff, ByteArray(FRAME_SIZE))
        assertTrue(ex.message!!.contains("unrecognised code"), ex.message)
        assertEquals(OperationError.WRONG_KEY, OperationError.fromCode(0x03))
    }

    // ----------------------------------------------------------------
    // Command timeout and late-response re-routing (fake-driven)
    // ----------------------------------------------------------------

    @Test
    fun `silent command times out and its late response routes to events`() = runBlocking {
        val fake = FakeLock()
        val session = FakeSessionChannel(fake)
        val lock = newLock(fake, session, commandTimeoutMs = 300)
        val eventList = mutableListOf<ByteArray>()
        val collectJob = launch(Dispatchers.Unconfined) {
            lock.events.collect { eventList += it }
        }

        lock.connect()
        session.silentWritesLeft = 1 // the next GetStatus gets no answer at all
        val ex = org.junit.jupiter.api.assertThrows<AugustTimeoutException> {
            runBlocking { lock.status() }
        }
        assertTrue(ex.message!!.contains("timed out after 300ms"), ex.message)

        // The stranded operation response finally "arrives" while the next
        // GetStatus is pending. It echoes opcode 0x0a — it must NOT be
        // consumed as the GetStatus response but re-routed to events.
        session.lateMarkerPending = true
        assertEquals(
            LockStatus.LOCKED, lock.status(),
            "the next command must still complete with its own response",
        )

        lock.disconnect()
        collectJob.cancel()
        assertEquals(1, eventList.size, "the late response must surface on events, exactly once")
        assertEquals(0x0a, eventList[0][1].toInt() and 0xff, "the late frame is an op response")
        assertEquals(0xef, eventList[0][0x08].toInt() and 0xff, "marker low byte")
        assertEquals(0xbe, eventList[0][0x09].toInt() and 0xff, "marker high byte")
    }

    @Test
    fun `silent operation times out with the acknowledgement state in the message`() = runBlocking {
        val fake = FakeLock()
        val session = FakeSessionChannel(fake)
        val lock = newLock(fake, session, commandTimeoutMs = 300)
        lock.connect()

        session.silentWritesLeft = 1
        val ex = org.junit.jupiter.api.assertThrows<AugustTimeoutException> {
            runBlocking { lock.lock() }
        }
        assertTrue(ex.message!!.contains("timed out after 300ms"), ex.message)
        assertTrue(ex.message!!.contains("acknowledged=false"), ex.message)

        lock.disconnect()
    }

    // ----------------------------------------------------------------
    // Lifecycle idempotency (fake-driven)
    // ----------------------------------------------------------------

    @Test
    fun `disconnect before connect is a silent no-op`() = runBlocking {
        val fake = FakeLock()
        val lock = newLock(fake, FakeSessionChannel(fake))

        lock.disconnect() // must neither throw nor touch the secure channel

        assertFalse(lock.isSecure)
        assertEquals(0, fake.secWrites, "no secure-channel write may happen before connect")
    }

    @Test
    fun `double connect re-handshakes and keeps the session working`() = runBlocking {
        val fake = FakeLock()
        val session = FakeSessionChannel(fake)
        val lock = newLock(fake, session)

        lock.connect()
        assertTrue(lock.isSecure)
        lock.connect() // a second handshake over the "same" connection

        assertTrue(lock.isSecure, "connect must end secure every time")
        assertEquals(
            LockStatus.LOCKED, lock.status(),
            "the session key must be re-established fresh, not a stale chained one",
        )
        assertEquals(4, fake.secWrites, "two connects = handshake twice (0x01 + 0x03 each)")
        lock.disconnect()
        assertEquals(5, fake.secWrites, "after two connects the disconnect still sends exactly one SEC 0x05")
        assertFalse(lock.isSecure)
    }

    @Test
    fun `double disconnect is idempotent and sends the terminate once`() = runBlocking {
        val fake = FakeLock()
        val lock = newLock(fake, FakeSessionChannel(fake))
        lock.connect()

        lock.disconnect()
        assertFalse(lock.isSecure)
        assertEquals(3, fake.secWrites, "connect (0x01 + 0x03) + one SEC 0x05 terminate")

        lock.disconnect() // must neither throw nor send a second terminate
        assertEquals(3, fake.secWrites, "the second disconnect must not write again")
        assertFalse(lock.isSecure)
    }

    // ----------------------------------------------------------------
    // Fakes (copied from AugustLockEventRoutingTest, extended only where
    // this file's cases need it: silent writes, late-response marker,
    // secure-write counting and re-handshake tolerance)
    // ----------------------------------------------------------------

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

        /** How many frames arrived on the secure channel (write counter for the lifecycle tests). */
        var secWrites = 0

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
                    // Session key = phone random[0..8) + lock random[0..8)
                    val sessionKey = ByteArray(16)
                    plain.copyInto(sessionKey, 0, 0x04, 0x0c)
                    lockRandom.copyInto(sessionKey, 8)
                    fake.secCipher = EcbBlockCipher(sessionKey)
                    fake.sesCipher = StatefulCbcCipher(sessionKey)
                }
                0x03 -> respond(0x04)
                0x05 -> {
                    respond(0x8b)
                    // The real lock closes the connection after the
                    // terminate; on the next connection its secure channel
                    // starts from the key-slot key again (this is what
                    // makes a second connect() work against the fake).
                    fake.secCipher = EcbBlockCipher(offlineKey)
                    fake.sesCipher = null
                }
                else -> error("unexpected SEC opcode ${plain[0]}")
            }
        }

        /**
         * The lock-side of a re-handshake over an established connection:
         * if the frame does not decrypt to a secure opcode under the key
         * the lock currently holds, it must have been sent with the
         * key-slot key, so fall back to that (a real lock resets to the
         * key slot when a host re-runs the key exchange).
         */
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

    private inner class FakeSessionChannel(private val fake: FakeLock) : GattChannel {
        private val out = Channel<ByteArray>(Channel.UNLIMITED)
        override val incoming: Flow<ByteArray> = out.receiveAsFlow()

        /**
         * Writes to swallow entirely — the lock never answers them. Used
         * by the timeout tests.
         */
        var silentWritesLeft = 0

        /**
         * When set, the next GetStatus write first emits the stranded
         * operation response (opcode 0x0a, marker 0xBEEF at byte 8/9)
         * that "arrived late" after a timed-out earlier command.
         */
        var lateMarkerPending = false

        private fun emit(frame: ByteArray) {
            out.trySend(fake.encryptSession(frame))
        }

        override suspend fun write(value: ByteArray) {
            // A swallowed write is still a delivered frame: the lock-side
            // CBC decrypt chain advances even when no reply is sent back.
            if (silentWritesLeft > 0) {
                silentWritesLeft--
                fake.decryptSession(value)
                return
            }
            val plain = fake.decryptSession(value)
            when (plain[1].toInt() and 0xff) {
                0x0a, 0x0b -> { // lock / unlock / unlatch / secure lock (staged)
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
                0x02 -> { // GetStatus
                    fake.statusIndexSeen = plain[4].toInt() and 0xff
                    if (lateMarkerPending) {
                        lateMarkerPending = false
                        // The op response of the timed-out earlier write,
                        // arriving late: it can never match a GetStatus.
                        val marker = fake.sessionFrame(0xbb, 0x0a)
                        marker[0x04] = 0x0a.toByte()
                        marker.writeUInt32LE(0x08, 0x0000beef)
                        fake.fixTrailer(marker)
                        emit(marker)
                    }
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
