package com.meshcipher.data.local.database

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "db_key_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getOrCreateKey(): ByteArray {
        val existing = prefs.getString(KEY_DB_PASSPHRASE, null)
        if (existing != null) {
            return java.util.Base64.getDecoder().decode(existing)
        }

        val key = ByteArray(32)
        SecureRandom().nextBytes(key)

        prefs.edit()
            .putString(KEY_DB_PASSPHRASE, java.util.Base64.getEncoder().encodeToString(key))
            .apply()

        return key
    }

    companion object {
        private const val KEY_DB_PASSPHRASE = "sqlcipher_key"
    }
}
