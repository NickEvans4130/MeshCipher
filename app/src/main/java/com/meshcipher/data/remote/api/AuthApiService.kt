package com.meshcipher.data.remote.api

import com.meshcipher.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface AuthApiService {

    @POST("api/v1/auth/challenge")
    suspend fun requestChallenge(
        @Body request: AuthChallengeRequest
    ): Response<AuthChallengeResponse>

    @POST("api/v1/auth/verify")
    suspend fun verifyChallenge(
        @Body request: AuthVerifyRequest
    ): Response<AuthVerifyResponse>

    /** GAP-04 / R-05: Fetch the relay's ES256 public key for local JWT verification. */
    @GET("api/v1/auth/public-key")
    suspend fun getRelayPublicKey(): Response<RelayPublicKeyResponse>
}
