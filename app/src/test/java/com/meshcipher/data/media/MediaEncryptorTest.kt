package com.meshcipher.data.media

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

class MediaEncryptorTest {

    private lateinit var mediaEncryptor: MediaEncryptor

    @Before
    fun setup() {
        mediaEncryptor = MediaEncryptor()
    }

    @Test
    fun `encrypt and decrypt round trip preserves data`() {
        val originalData = "Hello, this is test media data!".toByteArray()

        val encrypted = mediaEncryptor.encrypt(originalData)
        val decrypted = mediaEncryptor.decrypt(
            encrypted.encryptedBytes,
            encrypted.keyBase64,
            encrypted.ivBase64
        )

        assertArrayEquals(originalData, decrypted)
    }

    @Test
    fun `encrypt produces different ciphertext for same plaintext`() {
        val data = "Same data".toByteArray()

        val encrypted1 = mediaEncryptor.encrypt(data)
        val encrypted2 = mediaEncryptor.encrypt(data)

        // Different keys should produce different ciphertexts
        assertNotEquals(encrypted1.keyBase64, encrypted2.keyBase64)
    }

    @Test
    fun `encrypted data is different from plaintext`() {
        val data = "Plaintext content".toByteArray()

        val encrypted = mediaEncryptor.encrypt(data)

        assertFalse(data.contentEquals(encrypted.encryptedBytes))
    }

    @Test
    fun `key is valid base64 AES-256 key`() {
        val encrypted = mediaEncryptor.encrypt("test".toByteArray())

        val keyBytes = Base64.getDecoder().decode(encrypted.keyBase64)
        assertEquals(32, keyBytes.size) // 256 bits = 32 bytes
    }

    @Test
    fun `iv is valid base64`() {
        val encrypted = mediaEncryptor.encrypt("test".toByteArray())

        val ivBytes = Base64.getDecoder().decode(encrypted.ivBase64)
        assertEquals(12, ivBytes.size) // GCM standard IV size
    }

    @Test(expected = Exception::class)
    fun `decrypt with wrong key fails`() {
        val data = "Secret data".toByteArray()
        val encrypted = mediaEncryptor.encrypt(data)

        // Use a different key
        val differentEncrypted = mediaEncryptor.encrypt("other".toByteArray())

        mediaEncryptor.decrypt(
            encrypted.encryptedBytes,
            differentEncrypted.keyBase64,
            encrypted.ivBase64
        )
    }

    @Test
    fun `encrypt handles large data`() {
        val largeData = ByteArray(1024 * 1024) { it.toByte() } // 1MB

        val encrypted = mediaEncryptor.encrypt(largeData)
        val decrypted = mediaEncryptor.decrypt(
            encrypted.encryptedBytes,
            encrypted.keyBase64,
            encrypted.ivBase64
        )

        assertArrayEquals(largeData, decrypted)
    }

    @Test
    fun `encrypt handles empty data`() {
        val emptyData = ByteArray(0)

        val encrypted = mediaEncryptor.encrypt(emptyData)
        val decrypted = mediaEncryptor.decrypt(
            encrypted.encryptedBytes,
            encrypted.keyBase64,
            encrypted.ivBase64
        )

        assertArrayEquals(emptyData, decrypted)
    }
}
