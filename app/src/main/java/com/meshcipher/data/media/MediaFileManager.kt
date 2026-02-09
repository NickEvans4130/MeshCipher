package com.meshcipher.data.media

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.meshcipher.domain.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages media files with at-rest encryption.
 *
 * Every file is encrypted with a unique AES-256-GCM key before being written to disk.
 * The per-file key and IV are stored in EncryptedSharedPreferences, keyed by media ID.
 * Plaintext never touches the filesystem.
 */
@Singleton
class MediaFileManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val keyPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Encrypts [bytes] with a fresh AES-256-GCM key and writes the ciphertext to disk.
     * The per-file key and IV are stored in EncryptedSharedPreferences.
     * Returns the file path of the encrypted file on disk.
     */
    fun saveMedia(context: Context, mediaId: String, bytes: ByteArray, mediaType: MediaType): String {
        // Generate per-file key and encrypt
        val fileKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, ALGORITHM))
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(bytes)

        // Store per-file key and IV
        keyPrefs.edit()
            .putString("${PREFIX_KEY}$mediaId", Base64.getEncoder().encodeToString(fileKey))
            .putString("${PREFIX_IV}$mediaId", Base64.getEncoder().encodeToString(iv))
            .apply()

        // Write encrypted bytes to disk
        val dir = getMediaDir(context, mediaType)
        val file = File(dir, mediaId)
        file.writeBytes(encryptedBytes)

        Timber.d("Saved encrypted media %s (%d bytes plaintext, %d bytes on disk)",
            mediaId, bytes.size, encryptedBytes.size)
        return file.absolutePath
    }

    /**
     * Reads and decrypts a media file, returning the plaintext bytes.
     * Returns null if the file doesn't exist or decryption fails.
     */
    fun decryptMedia(mediaId: String, mediaType: MediaType): ByteArray? {
        val file = File(getMediaDir(context, mediaType), mediaId)
        if (!file.exists()) return null

        val keyB64 = keyPrefs.getString("${PREFIX_KEY}$mediaId", null) ?: return null
        val ivB64 = keyPrefs.getString("${PREFIX_IV}$mediaId", null) ?: return null

        return try {
            val fileKey = Base64.getDecoder().decode(keyB64)
            val iv = Base64.getDecoder().decode(ivB64)
            val encryptedBytes = file.readBytes()

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey, ALGORITHM),
                GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(encryptedBytes)
        } catch (e: Exception) {
            Timber.e(e, "Failed to decrypt media: %s", mediaId)
            null
        }
    }

    /**
     * Decrypts media to a temporary file for playback (video/voice).
     * Caller is responsible for deleting the temp file when done.
     */
    fun decryptMediaToTempFile(mediaId: String, mediaType: MediaType): File? {
        val plaintext = decryptMedia(mediaId, mediaType) ?: return null
        val ext = when (mediaType) {
            MediaType.IMAGE -> ".jpg"
            MediaType.VIDEO -> ".mp4"
            MediaType.VOICE -> ".aac"
        }
        val tempFile = File(context.cacheDir, "dec_${mediaId}$ext")
        tempFile.writeBytes(plaintext)
        return tempFile
    }

    fun getMediaFile(context: Context, mediaId: String, mediaType: MediaType): File? {
        val file = File(getMediaDir(context, mediaType), mediaId)
        return if (file.exists()) file else null
    }

    fun deleteMedia(context: Context, mediaId: String, mediaType: MediaType) {
        val file = File(getMediaDir(context, mediaType), mediaId)
        if (file.exists()) {
            file.delete()
            Timber.d("Deleted media: %s", mediaId)
        }
        // Clean up keys
        keyPrefs.edit()
            .remove("${PREFIX_KEY}$mediaId")
            .remove("${PREFIX_IV}$mediaId")
            .apply()
    }

    fun getMediaDir(context: Context, mediaType: MediaType): File {
        val dir = File(context.filesDir, "media_encrypted/${mediaType.name.lowercase()}")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun cleanupAllMedia(context: Context) {
        val mediaRoot = File(context.filesDir, "media_encrypted")
        if (mediaRoot.exists()) {
            mediaRoot.deleteRecursively()
            Timber.d("Cleaned up all encrypted media files")
        }
        // Also clean legacy unencrypted media
        val legacyRoot = File(context.filesDir, "media")
        if (legacyRoot.exists()) {
            legacyRoot.deleteRecursively()
            Timber.d("Cleaned up legacy plaintext media files")
        }
        // Clear all keys
        keyPrefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "media_encryption_keys"
        private const val PREFIX_KEY = "key:"
        private const val PREFIX_IV = "iv:"
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
