package com.meshcipher.data.media

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

data class EncryptedMedia(
    val encryptedBytes: ByteArray,
    val keyBase64: String,
    val ivBase64: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedMedia
        return encryptedBytes.contentEquals(other.encryptedBytes) &&
            keyBase64 == other.keyBase64 &&
            ivBase64 == other.ivBase64
    }

    override fun hashCode(): Int {
        var result = encryptedBytes.contentHashCode()
        result = 31 * result + keyBase64.hashCode()
        result = 31 * result + ivBase64.hashCode()
        return result
    }
}

@Singleton
class MediaEncryptor @Inject constructor() {

    fun encrypt(plainBytes: ByteArray): EncryptedMedia {
        val keyGen = KeyGenerator.getInstance(ALGORITHM)
        keyGen.init(KEY_SIZE_BITS)
        val secretKey = keyGen.generateKey()

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plainBytes)

        return EncryptedMedia(
            encryptedBytes = encryptedBytes,
            keyBase64 = Base64.getEncoder().encodeToString(secretKey.encoded),
            ivBase64 = Base64.getEncoder().encodeToString(iv)
        )
    }

    fun decrypt(encryptedBytes: ByteArray, keyBase64: String, ivBase64: String): ByteArray {
        val keyBytes = Base64.getDecoder().decode(keyBase64)
        val ivBytes = Base64.getDecoder().decode(ivBase64)

        val secretKey = SecretKeySpec(keyBytes, ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivBytes)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        return cipher.doFinal(encryptedBytes)
    }

    companion object {
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
