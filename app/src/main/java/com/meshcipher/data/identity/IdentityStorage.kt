package com.meshcipher.data.identity

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.meshcipher.domain.model.Identity
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = try {
        createEncryptedPrefs()
    } catch (e: Exception) {
        Timber.w(e, "EncryptedSharedPreferences corrupted, clearing and recreating")
        // Delete the corrupted prefs file and retry
        File(context.filesDir.parent, "shared_prefs/$PREFS_NAME.xml").delete()
        try {
            createEncryptedPrefs()
        } catch (e2: Exception) {
            Timber.e(e2, "Failed to recreate EncryptedSharedPreferences, using fallback")
            context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveIdentity(identity: Identity) {
        val json = gson.toJson(identity)
        prefs.edit().putString(KEY_IDENTITY, json).apply()
    }

    fun getIdentity(): Identity? {
        val json = prefs.getString(KEY_IDENTITY, null) ?: return null
        return gson.fromJson(json, Identity::class.java)
    }

    fun hasIdentity(): Boolean {
        return prefs.contains(KEY_IDENTITY)
    }

    fun deleteIdentity() {
        prefs.edit().remove(KEY_IDENTITY).apply()
    }

    fun markRecoverySetup() {
        val identity = getIdentity() ?: return
        saveIdentity(identity.copy(recoverySetup = true))
    }

    companion object {
        private const val PREFS_NAME = "identity_prefs"
        private const val KEY_IDENTITY = "identity"
    }
}
