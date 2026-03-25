package com.meshcipher.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.auth0.android.jwt.JWT
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "auth_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun getToken(): String? {
        return prefs.getString(KEY_TOKEN, null)
    }

    fun hasValidToken(): Boolean {
        val token = getToken() ?: return false

        return try {
            val jwt = JWT(token)
            !jwt.isExpired(10)
        } catch (e: Exception) {
            false
        }
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    // GAP-04 / R-05: Persist the relay's ES256 public key so we can verify JWT signatures
    // locally on every subsequent auth check. Stored in EncryptedSharedPreferences alongside
    // the token, so it receives the same at-rest protection.

    fun saveRelayPublicKey(pemKey: String) {
        prefs.edit().putString(KEY_RELAY_PUBLIC_KEY, pemKey).apply()
    }

    fun getRelayPublicKey(): String? = prefs.getString(KEY_RELAY_PUBLIC_KEY, null)

    /**
     * Returns true if [token] is syntactically valid, not expired, and its ES256 signature
     * verifies against the stored relay public key.
     *
     * If no public key is stored yet (first run before key has been fetched), expiry-only
     * validation is used as a temporary fallback — the caller must fetch and store the key.
     */
    fun hasValidToken(): Boolean {
        val token = getToken() ?: return false
        val notExpired = try {
            val jwt = JWT(token)
            !jwt.isExpired(10)
        } catch (e: Exception) {
            return false
        }
        if (!notExpired) return false

        val publicKey = getRelayPublicKey() ?: return true // key not yet fetched — expiry only
        return try {
            JwtSignatureVerifier.verify(token, publicKey)
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_RELAY_PUBLIC_KEY = "relay_jwt_public_key"
    }
}
