package io.github.bataokun.augustble

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private fun ecbCipher(mode: Int, key: ByteArray): Cipher {
    require(key.size == 16) { "AES-128 key must be 16 bytes, got ${key.size}" }
    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    cipher.init(mode, SecretKeySpec(key.copyOf(), "AES"))
    return cipher
}

/**
 * AES-128-ECB over a single 16-byte block (the secure channel's cipher).
 * ECB is stateless, so [setKey] simply replaces the ciphers.
 */
internal class EcbBlockCipher(key: ByteArray) {
    private var encryptCipher = ecbCipher(Cipher.ENCRYPT_MODE, key)
    private var decryptCipher = ecbCipher(Cipher.DECRYPT_MODE, key)

    fun setKey(key: ByteArray) {
        encryptCipher = ecbCipher(Cipher.ENCRYPT_MODE, key)
        decryptCipher = ecbCipher(Cipher.DECRYPT_MODE, key)
    }

    @Synchronized
    fun encryptBlock(block: ByteArray): ByteArray {
        require(block.size == 16)
        return encryptCipher.doFinal(block)
    }

    @Synchronized
    fun decryptBlock(block: ByteArray): ByteArray {
        require(block.size == 16)
        return decryptCipher.doFinal(block)
    }
}

/**
 * AES-128-CBC with a zero IV and no padding, **stateful across messages**.
 *
 * This is the single most important detail of the protocol: one cipher
 * object is created per [setKey] and reused for every frame, so the CBC
 * chain (previous ciphertext block) carries over from one 18-byte frame
 * to the next. Recreating a fresh zero-IV CBC cipher per message — the
 * obvious implementation — works for the first command and silently
 * breaks every command after it.
 *
 * This class therefore implements CBC manually on top of raw AES blocks
 * and keeps the chaining blocks as member state. [setKey] resets the
 * chain and installs fresh ciphers.
 */
internal class StatefulCbcCipher(key: ByteArray) {
    private var encryptCipher = ecbCipher(Cipher.ENCRYPT_MODE, key)
    private var decryptCipher = ecbCipher(Cipher.DECRYPT_MODE, key)
    private var encryptChain = ByteArray(16) // zero IV
    private var decryptChain = ByteArray(16) // zero IV

    fun setKey(key: ByteArray) {
        encryptCipher = ecbCipher(Cipher.ENCRYPT_MODE, key)
        decryptCipher = ecbCipher(Cipher.DECRYPT_MODE, key)
        encryptChain = ByteArray(16)
        decryptChain = ByteArray(16)
    }

    @Synchronized
    fun encryptBlock(block: ByteArray): ByteArray {
        require(block.size == 16)
        val xored = ByteArray(16) { i -> (block[i].toInt() xor encryptChain[i].toInt()).toByte() }
        val out = encryptCipher.doFinal(xored)
        encryptChain = out.copyOf()
        return out
    }

    @Synchronized
    fun decryptBlock(block: ByteArray): ByteArray {
        require(block.size == 16)
        val raw = decryptCipher.doFinal(block)
        val out = ByteArray(16) { i -> (raw[i].toInt() xor decryptChain[i].toInt()).toByte() }
        decryptChain = block.copyOf()
        return out
    }
}
