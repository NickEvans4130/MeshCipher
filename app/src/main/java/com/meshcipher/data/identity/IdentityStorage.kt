package com.meshcipher.data.identity

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.meshcipher.domain.model.Identity
import dagger.hilt.android.qualifiers.ApplicationContext
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

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "identity_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

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
        private const val KEY_IDENTITY = "identity"
    }
}
