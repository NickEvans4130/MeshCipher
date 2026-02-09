package com.meshcipher.data.media

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tests the AES-256-GCM at-rest encryption logic used by MediaFileManager.
 * This exercises the same cryptographic operations without requiring Android context.
 */
class MediaAtRestEncryptionTest {

    private val algorithm = "AES"
    private val transformation = "AES/GCM/NoPadding"
    private val gcmTagBits = 128

    private fun encryptAtRest(plaintext: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, algorithm))
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return Triple(ciphertext, key, iv)
    }

    private fun decryptAtRest(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, algorithm), GCMParameterSpec(gcmTagBits, iv))
        return cipher.doFinal(ciphertext)
    }

    @Test
    fun `at-rest encrypt and decrypt round trip preserves data`() {
        val original = "Sensitive media content for testing".toByteArray()
        val (ciphertext, key, iv) = encryptAtRest(original)
        val decrypted = decryptAtRest(ciphertext, key, iv)
        assertArrayEquals(original, decrypted)
    }

    @Test
    fun `at-rest ciphertext differs from plaintext`() {
        val original = "Plaintext data".toByteArray()
        val (ciphertext, _, _) = encryptAtRest(original)
        assertFalse(original.contentEquals(ciphertext))
    }

    @Test
    fun `at-rest encryption uses unique key per file`() {
        val data = "Same data".toByteArray()
        val (_, key1, _) = encryptAtRest(data)
        val (_, key2, _) = encryptAtRest(data)
        assertFalse(key1.contentEquals(key2))
    }

    @Test
    fun `at-rest encryption uses unique IV per file`() {
        val data = "Same data".toByteArray()
        val (_, _, iv1) = encryptAtRest(data)
        val (_, _, iv2) = encryptAtRest(data)
        assertFalse(iv1.contentEquals(iv2))
    }

    @Test(expected = Exception::class)
    fun `at-rest decrypt with wrong key fails`() {
        val data = "Secret data".toByteArray()
        val (ciphertext, _, iv) = encryptAtRest(data)
        val wrongKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        decryptAtRest(ciphertext, wrongKey, iv)
    }

    @Test(expected = Exception::class)
    fun `at-rest decrypt with wrong IV fails`() {
        val data = "Secret data".toByteArray()
        val (ciphertext, key, _) = encryptAtRest(data)
        val wrongIv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        decryptAtRest(ciphertext, key, wrongIv)
    }

    @Test(expected = Exception::class)
    fun `at-rest decrypt with tampered ciphertext fails`() {
        val data = "Secret data".toByteArray()
        val (ciphertext, key, iv) = encryptAtRest(data)
        // Tamper with the ciphertext
        ciphertext[0] = (ciphertext[0].toInt() xor 0xFF).toByte()
        decryptAtRest(ciphertext, key, iv)
    }

    @Test
    fun `at-rest handles large media files`() {
        val largeData = ByteArray(5 * 1024 * 1024) { it.toByte() } // 5MB
        val (ciphertext, key, iv) = encryptAtRest(largeData)
        val decrypted = decryptAtRest(ciphertext, key, iv)
        assertArrayEquals(largeData, decrypted)
    }

    @Test
    fun `at-rest handles empty data`() {
        val empty = ByteArray(0)
        val (ciphertext, key, iv) = encryptAtRest(empty)
        val decrypted = decryptAtRest(ciphertext, key, iv)
        assertArrayEquals(empty, decrypted)
    }

    @Test
    fun `GCM tag is appended to ciphertext`() {
        val data = "Test".toByteArray()
        val (ciphertext, _, _) = encryptAtRest(data)
        // GCM appends a 16-byte (128-bit) auth tag
        assertEquals(data.size + 16, ciphertext.size)
    }

    @Test
    fun `key is 256 bits`() {
        val (_, key, _) = encryptAtRest("test".toByteArray())
        assertEquals(32, key.size)
    }

    @Test
    fun `IV is 12 bytes for GCM`() {
        val (_, _, iv) = encryptAtRest("test".toByteArray())
        assertEquals(12, iv.size)
    }
}
