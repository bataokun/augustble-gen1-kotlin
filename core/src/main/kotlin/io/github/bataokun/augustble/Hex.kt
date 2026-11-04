package io.github.bataokun.augustble

/** Small hex helpers (keys are handled as hex strings). */
fun ByteArray.toHexString(): String {
    val digits = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(digits[v ushr 4]).append(digits[v and 0x0f])
    }
    return sb.toString()
}

fun String.hexToBytes(): ByteArray {
    val clean = replace(Regex("[^0-9a-fA-F]"), "")
    require(clean.length % 2 == 0) { "hex string must have an even number of digits" }
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
