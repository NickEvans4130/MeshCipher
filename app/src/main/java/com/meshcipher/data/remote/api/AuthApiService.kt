package com.meshcipher.data.remote.api

import com.meshcipher.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.Body
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
}
