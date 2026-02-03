package com.meshcipher.data.auth

import android.util.Base64
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.remote.api.AuthApiService
import com.meshcipher.data.remote.dto.AuthChallengeRequest
import com.meshcipher.data.remote.dto.AuthVerifyRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthenticationManager @Inject constructor(
    private val identityManager: IdentityManager,
    private val authApi: AuthApiService,
    private val tokenStorage: TokenStorage
) {

    suspend fun authenticate(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val identity = identityManager.getIdentity()
                ?: return@withContext Result.failure(Exception("No identity"))

            // Step 1: Get challenge from server
            val challengeResponse = authApi.requestChallenge(
                AuthChallengeRequest(
                    userId = identity.userId,
                    publicKey = Base64.encodeToString(
                        identity.hardwarePublicKey,
                        Base64.NO_WRAP
                    )
                )
            )

            if (!challengeResponse.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to get challenge: ${challengeResponse.code()}")
                )
            }

            val challenge = Base64.decode(
                challengeResponse.body()!!.challenge,
                Base64.NO_WRAP
            )

            // Step 2: Sign challenge with hardware key
            val signature = identityManager.signChallenge(challenge)

            // Step 3: Verify signature with server
            val verifyResponse = authApi.verifyChallenge(
                AuthVerifyRequest(
                    userId = identity.userId,
                    challenge = challengeResponse.body()!!.challenge,
                    signature = Base64.encodeToString(signature, Base64.NO_WRAP)
                )
            )

            if (!verifyResponse.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Authentication failed: ${verifyResponse.code()}")
                )
            }

            val token = verifyResponse.body()!!.token
            tokenStorage.saveToken(token)

            Timber.d("Authentication successful")
            Result.success(token)

        } catch (e: Exception) {
            Timber.e(e, "Authentication failed")
            Result.failure(e)
        }
    }

    fun isAuthenticated(): Boolean {
        return tokenStorage.hasValidToken()
    }

    fun getToken(): String? {
        return tokenStorage.getToken()
    }

    fun logout() {
        tokenStorage.clearToken()
    }
}
