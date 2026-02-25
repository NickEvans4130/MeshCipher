package com.meshcipher.desktop.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric AES-256-GCM message encryption for desktop sessions.
 *
 * For full end-to-end encryption, the session key should be derived from
 * a Signal Protocol X3DH key exchange. This class handles the symmetric
 * portion once a shared secret is established.
 */
class MessageCrypto(sessionKeyBytes: ByteArray) {

    private val key = SecretKeySpec(sessionKeyBytes, "AES")

    fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, SecureRandom())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return iv + ciphertext // 12-byte IV prepended
    }

    fun decrypt(ivAndCiphertext: ByteArray): String {
        val iv = ivAndCiphertext.copyOf(12)
        val ciphertext = ivAndCiphertext.copyOfRange(12, ivAndCiphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    companion object {
        fun generateSessionKey(): ByteArray {
            val key = ByteArray(32)
            SecureRandom().nextBytes(key)
            return key
        }

        fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
        fun fromHex(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
