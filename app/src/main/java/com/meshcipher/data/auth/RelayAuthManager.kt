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
import okhttp3.CertificatePinner
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

    // GAP-03 / R-04: Build the plain OkHttp client using the actual relay host so the
    // certificate pin is applied to the host the user has configured, not a compile-time
    // constant that may differ from the runtime relayServerUrl.
    @Volatile
    private var cachedAuthClient: Pair<String, OkHttpClient>? = null

    private fun plainClientFor(host: String): OkHttpClient {
        val cached = cachedAuthClient
        if (cached != null && cached.first == host) return cached.second
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .certificatePinner(
                CertificatePinner.Builder()
                    .add(host, CertificatePins.RELAY_CERT_PIN_PRIMARY)
                    .add(host, CertificatePins.RELAY_CERT_PIN_BACKUP)
                    .build()
            )
            .build()
        cachedAuthClient = Pair(host, client)
        return client
    }

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
                val host = baseUrl.toHttpUrlOrNull()?.host
                    ?: run {
                        Timber.e("Cannot derive host from relay URL: %s", baseUrl)
                        return@withContext null
                    }

                // Step 0: Fetch relay public key (GAP-04 / R-05) — mandatory before
                // accepting any token.  Fail authentication if the key cannot be obtained.
                if (tokenStorage.getRelayPublicKey() == null) {
                    val fetched = fetchAndStorePublicKey(baseUrl, host)
                    if (!fetched) {
                        Timber.e("Could not obtain relay public key — aborting authentication")
                        return@withContext null
                    }
                }

                // Step 1: Register device
                registerDevice(baseUrl, host, identity)

                // Step 2: Authenticate (challenge-response)
                val token = authenticate(baseUrl, host, identity)
                if (token != null) {
                    // Verify JWT signature before trusting and storing the token.
                    // On failure: clear stale key, re-fetch, and retry once (key rotation).
                    val verified = verifyTokenWithRetry(token, baseUrl, host)
                    if (!verified) return@withContext null
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

    /**
     * Verifies [token]'s ES256 signature against the stored relay public key.
     * On failure, clears the cached key, re-fetches it, and retries verification once
     * to handle relay key rotation without requiring a full re-auth cycle.
     */
    private fun verifyTokenWithRetry(token: String, baseUrl: String, host: String): Boolean {
        val pubKey = tokenStorage.getRelayPublicKey() ?: run {
            Timber.e("No relay public key available for JWT verification")
            return false
        }
        val firstAttempt = runCatching { JwtSignatureVerifier.verify(token, pubKey) }
        if (firstAttempt.getOrDefault(false)) return true

        Timber.w("JWT signature verification failed — clearing key and retrying once")
        tokenStorage.clearToken() // also clears KEY_RELAY_PUBLIC_KEY per fix in TokenStorage
        val refetched = fetchAndStorePublicKey(baseUrl, host)
        if (!refetched) {
            Timber.e("Re-fetch of relay public key failed — cannot verify token")
            return false
        }
        val retryKey = tokenStorage.getRelayPublicKey() ?: return false
        return runCatching { JwtSignatureVerifier.verify(token, retryKey) }.getOrElse {
            Timber.e(it, "JWT signature verification failed after key refresh — relay may be compromised")
            false
        }
    }

    private fun registerDevice(baseUrl: String, host: String, identity: Identity) {
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

        val response = plainClientFor(host).newCall(request).execute()
        response.use {
            if (it.isSuccessful) {
                Timber.d("Device registered with relay: %s", it.body?.string())
            } else {
                Timber.w("Device registration returned %d: %s", it.code, it.body?.string())
            }
        }
    }

    private fun authenticate(baseUrl: String, host: String, identity: Identity): String? {
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

        val challengeResponse = plainClientFor(host).newCall(challengeRequest).execute()
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

        val verifyResponse = plainClientFor(host).newCall(verifyRequest).execute()
        verifyResponse.use {
            if (!it.isSuccessful) {
                Timber.e("Verify request failed: %d %s", it.code, it.body?.string())
                return null
            }
            val responseJson = JSONObject(it.body?.string() ?: "{}")
            return responseJson.optString("token", "").ifEmpty { null }
        }
    }

    /**
     * Fetches the relay's ES256 public key and stores it for JWT signature verification.
     * GAP-04 / R-05: Called on first run (mandatory) and on signature verification failure
     * (retry). Returns true if the key was successfully fetched and stored.
     */
    private fun fetchAndStorePublicKey(baseUrl: String, host: String): Boolean {
        val url = buildUrl(baseUrl, "api/v1/auth/public-key") ?: return false
        val request = Request.Builder().url(url).get().build()
        return try {
            plainClientFor(host).newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return false
                    val json = org.json.JSONObject(body)
                    val pem = json.optString("public_key", "")
                    if (pem.isNotBlank()) {
                        tokenStorage.saveRelayPublicKey(pem)
                        Timber.d("Relay public key fetched and stored")
                        true
                    } else {
                        Timber.w("Relay public-key response contained empty key")
                        false
                    }
                } else {
                    Timber.w("Failed to fetch relay public key: %d", response.code)
                    false
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not fetch relay public key")
            false
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
