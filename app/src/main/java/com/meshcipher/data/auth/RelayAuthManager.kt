package com.meshcipher.data.auth

import android.util.Base64
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.identity.KeyManager
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.domain.model.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles device registration and challenge-response authentication with the relay server.
 * Uses raw OkHttp calls (without interceptors) to avoid circular dependency with AuthInterceptor.
 */
@Singleton
class RelayAuthManager @Inject constructor(
    private val identityManager: IdentityManager,
    private val keyManager: KeyManager,
    private val tokenStorage: TokenStorage,
    private val appPreferences: AppPreferences
) {

    private val mutex = Mutex()

    // Plain OkHttp client without auth interceptors to avoid circular calls
    private val plainClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Ensures the device is registered and authenticated with the relay.
     * Returns a valid JWT token, or null if authentication failed.
     */
    suspend fun ensureAuthenticated(): String? = mutex.withLock {
        // Check if we already have a valid token (another thread may have obtained one)
        if (tokenStorage.hasValidToken()) {
            return tokenStorage.getToken()
        }

        return withContext(Dispatchers.IO) {
            try {
                val identity = identityManager.getIdentity()
                if (identity == null) {
                    Timber.w("No identity available for relay authentication")
                    return@withContext null
                }

                val baseUrl = appPreferences.relayServerUrl.first()

                // Step 1: Register device
                registerDevice(baseUrl, identity)

                // Step 2: Authenticate (challenge-response)
                val token = authenticate(baseUrl, identity)
                if (token != null) {
                    tokenStorage.saveToken(token)
                    Timber.d("Relay authentication successful")
                }
                token
            } catch (e: Exception) {
                Timber.e(e, "Relay authentication failed")
                null
            }
        }
    }

    private fun registerDevice(baseUrl: String, identity: Identity) {
        val url = buildUrl(baseUrl, "api/v1/register") ?: return

        val relayPublicKey = keyManager.getOrCreateRelayAuthKey()
        val body = JSONObject().apply {
            put("device_id", identity.deviceId)
            put("user_id", identity.userId)
            put("public_key", Base64.encodeToString(relayPublicKey.encoded, Base64.NO_WRAP))
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val response = plainClient.newCall(request).execute()
        response.use {
            if (it.isSuccessful) {
                Timber.d("Device registered with relay: %s", it.body?.string())
            } else {
                Timber.w("Device registration returned %d: %s", it.code, it.body?.string())
            }
        }
    }

    private fun authenticate(baseUrl: String, identity: Identity): String? {
        // Step 1: Request challenge
        val challengeUrl = buildUrl(baseUrl, "api/v1/auth/challenge") ?: return null

        val relayPublicKey = keyManager.getOrCreateRelayAuthKey()
        val challengeBody = JSONObject().apply {
            put("userId", identity.userId)
            put("publicKey", Base64.encodeToString(relayPublicKey.encoded, Base64.NO_WRAP))
        }

        val challengeRequest = Request.Builder()
            .url(challengeUrl)
            .post(challengeBody.toString().toRequestBody(jsonMediaType))
            .build()

        val challengeResponse = plainClient.newCall(challengeRequest).execute()
        val challengeB64: String
        challengeResponse.use {
            if (!it.isSuccessful) {
                Timber.e("Challenge request failed: %d %s", it.code, it.body?.string())
                return null
            }
            val responseJson = JSONObject(it.body?.string() ?: "{}")
            challengeB64 = responseJson.optString("challenge", "")
            if (challengeB64.isEmpty()) {
                Timber.e("Empty challenge in response")
                return null
            }
        }

        // Step 2: Sign the challenge with relay auth key (no biometric required)
        val challengeBytes = Base64.decode(challengeB64, Base64.NO_WRAP)
        val signature = keyManager.signWithRelayAuthKey(challengeBytes)

        // Step 3: Verify with server
        val verifyUrl = buildUrl(baseUrl, "api/v1/auth/verify") ?: return null

        val verifyBody = JSONObject().apply {
            put("userId", identity.userId)
            put("challenge", challengeB64)
            put("signature", Base64.encodeToString(signature, Base64.NO_WRAP))
        }

        val verifyRequest = Request.Builder()
            .url(verifyUrl)
            .post(verifyBody.toString().toRequestBody(jsonMediaType))
            .build()

        val verifyResponse = plainClient.newCall(verifyRequest).execute()
        verifyResponse.use {
            if (!it.isSuccessful) {
                Timber.e("Verify request failed: %d %s", it.code, it.body?.string())
                return null
            }
            val responseJson = JSONObject(it.body?.string() ?: "{}")
            return responseJson.optString("token", "").ifEmpty { null }
        }
    }

    private fun buildUrl(baseUrl: String, path: String): String? {
        val base = baseUrl.trimEnd('/')
        val url = "$base/$path"
        if (url.toHttpUrlOrNull() == null) {
            Timber.e("Invalid URL: %s", url)
            return null
        }
        return url
    }
}
