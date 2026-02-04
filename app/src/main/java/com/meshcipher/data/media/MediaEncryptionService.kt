package com.meshcipher.data.media

import android.util.Base64
import com.meshcipher.domain.model.EncryptedMediaChunk
import com.meshcipher.domain.model.MediaChunk
import timber.log.Timber
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles AES-256-GCM encryption/decryption of media chunks.
 *
 * Each chunk gets a unique random key and nonce for perfect forward secrecy.
 * Chunk keys are shared via the message metadata (encrypted with Signal Protocol).
 */
@Singleton
class MediaEncryptionService @Inject constructor() {

    private val secureRandom = SecureRandom()

    /**
     * Encrypts a media chunk with AES-256-GCM.
     *
     * @return EncryptedMediaChunk containing ciphertext, key, and nonce
     */
    fun encryptChunk(chunk: MediaChunk): EncryptedMediaChunk {
        val key = ByteArray(KEY_SIZE).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(NONCE_SIZE).also { secureRandom.nextBytes(it) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TAG_BIT_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val encryptedData = cipher.doFinal(chunk.data)

        return EncryptedMediaChunk(
            index = chunk.index,
            encryptedData = encryptedData,
            nonce = nonce,
            chunkKey = key,
            originalHash = chunk.hash
        )
    }

    /**
     * Decrypts an encrypted media chunk.
     *
     * @return Decrypted data bytes
     * @throws Exception if decryption or integrity check fails
     */
    fun decryptChunk(encrypted: EncryptedMediaChunk): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(encrypted.chunkKey, "AES")
        val gcmSpec = GCMParameterSpec(TAG_BIT_LENGTH, encrypted.nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        val decryptedData = cipher.doFinal(encrypted.encryptedData)

        // Verify integrity
        val hash = MediaChunk.computeHash(decryptedData)
        if (hash != encrypted.originalHash) {
            throw SecurityException(
                "Chunk ${encrypted.index} integrity check failed: expected ${encrypted.originalHash}, got $hash"
            )
        }

        return decryptedData
    }

    /**
     * Encrypts arbitrary data (used for thumbnails).
     */
    fun encrypt(data: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val key = ByteArray(KEY_SIZE).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(NONCE_SIZE).also { secureRandom.nextBytes(it) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TAG_BIT_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val encrypted = cipher.doFinal(data)
        return Triple(encrypted, key, nonce)
    }

    /**
     * Decrypts arbitrary data (used for thumbnails).
     */
    fun decrypt(data: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TAG_BIT_LENGTH, nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        return cipher.doFinal(data)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE = 32 // 256 bits
        private const val NONCE_SIZE = 12 // 96 bits
        private const val TAG_BIT_LENGTH = 128

        fun encodeKey(key: ByteArray): String = Base64.encodeToString(key, Base64.NO_WRAP)
        fun decodeKey(encoded: String): ByteArray = Base64.decode(encoded, Base64.NO_WRAP)
    }
}
