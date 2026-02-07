package com.meshcipher.data.auth

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStorage: TokenStorage
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Skip auth header for public endpoints
        val path = original.url.encodedPath
        if (path.contains("/auth/") || path.contains("/health") || path.contains("/register")) {
            return chain.proceed(original)
        }

        val token = tokenStorage.getToken()
        if (token != null) {
            val authenticated = original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            return chain.proceed(authenticated)
        }

        return chain.proceed(original)
    }
}
