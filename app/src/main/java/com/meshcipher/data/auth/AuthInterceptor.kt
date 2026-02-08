package com.meshcipher.data.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStorage: TokenStorage,
    private val relayAuthManager: RelayAuthManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Skip auth header for public endpoints
        val path = original.url.encodedPath
        if (path.contains("/auth/") || path.contains("/health") || path.contains("/register")) {
            return chain.proceed(original)
        }

        // If we have a valid token, attach it
        val token = tokenStorage.getToken()
        if (token != null && tokenStorage.hasValidToken()) {
            val authenticated = original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            val response = chain.proceed(authenticated)

            // If token was rejected, try re-authenticating
            if (response.code == 401) {
                response.close()
                val newToken = runBlocking { relayAuthManager.ensureAuthenticated() }
                if (newToken != null) {
                    val retry = original.newBuilder()
                        .header("Authorization", "Bearer $newToken")
                        .build()
                    return chain.proceed(retry)
                }
            }
            return response
        }

        // No valid token - register and authenticate before proceeding
        val newToken = runBlocking { relayAuthManager.ensureAuthenticated() }
        if (newToken != null) {
            val authenticated = original.newBuilder()
                .header("Authorization", "Bearer $newToken")
                .build()
            return chain.proceed(authenticated)
        }

        Timber.w("No auth token available, proceeding without authentication")
        return chain.proceed(original)
    }
}
