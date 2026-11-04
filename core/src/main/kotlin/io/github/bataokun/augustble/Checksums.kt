package io.github.bataokun.augustble

/** Frame size used by every August Gen1 command/response: 0x12 = 18 bytes. */
const val FRAME_SIZE = 0x12

internal fun ByteArray.readUInt32LE(offset: Int): Long {
    return (this[offset].toLong() and 0xff) or
        ((this[offset + 1].toLong() and 0xff) shl 8) or
        ((this[offset + 2].toLong() and 0xff) shl 16) or
        ((this[offset + 3].toLong() and 0xff) shl 24)
}

internal fun ByteArray.writeUInt32LE(offset: Int, value: Long) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value ushr 8) and 0xff).toByte()
    this[offset + 2] = ((value ushr 16) and 0xff).toByte()
    this[offset + 3] = ((value ushr 24) and 0xff).toByte()
}

/**
 * Frame checksums (see PROTOCOL.md).
 */
object Checksums {
    /** Sum of all 18 bytes mod 256, negated. A valid frame sums to 0 mod 256. */
    fun simple(frame: ByteArray): Int {
        var cs = 0
        for (i in 0 until FRAME_SIZE) {
            cs = (cs + (frame[i].toInt() and 0xff)) and 0xff
        }
        return (-cs) and 0xff
    }

    /** `-(w0 + w1 + w2)` over the first three little-endian uint32 words. */
    fun security(frame: ByteArray): Long {
        val sum = frame.readUInt32LE(0x00) + frame.readUInt32LE(0x04) + frame.readUInt32LE(0x08)
        return (-sum) and 0xFFFF_FFFFL
    }
}
