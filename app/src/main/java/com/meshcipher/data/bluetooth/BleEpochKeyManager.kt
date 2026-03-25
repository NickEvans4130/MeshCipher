package com.meshcipher.data.bluetooth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GAP-01 / R-01: Manages the per-install 256-bit secret used to derive epoch-specific BLE
 * advertisement pseudonyms.
 *
 * Pseudonym derivation: HMAC-SHA256(secret, epochNumber as 8-byte big-endian) → first 16 bytes
 * interpreted as a UUID.  The epoch number is `currentTimeMillis / EPOCH_DURATION_MS`, so every
 * device in range computes the same UUID for the same epoch without any out-of-band coordination.
 *
 * The secret is generated once, stored in EncryptedSharedPreferences, and never leaves the device.
 */
@Singleton
class BleEpochKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ble_epoch_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val secret: ByteArray by lazy { loadOrGenerateSecret() }

    fun epochNumber(): Long = System.currentTimeMillis() / EPOCH_DURATION_MS

    fun epochUuid(epochNumber: Long): UUID {
        val epochBytes = ByteBuffer.allocate(8).putLong(epochNumber).array()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val digest = mac.doFinal(epochBytes)
        // Use first 16 bytes as UUID raw bytes
        val msb = ByteBuffer.wrap(digest, 0, 8).long
        val lsb = ByteBuffer.wrap(digest, 8, 8).long
        return UUID(msb, lsb)
    }

    private fun loadOrGenerateSecret(): ByteArray {
        val stored = prefs.getString(KEY_SECRET, null)
        if (stored != null) {
            return Base64.getDecoder().decode(stored)
        }
        val newSecret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_SECRET, Base64.getEncoder().encodeToString(newSecret)).apply()
        return newSecret
    }

    companion object {
        const val EPOCH_DURATION_MS = 15 * 60 * 1000L // 15 minutes
        private const val KEY_SECRET = "ble_epoch_secret"
    }
}
