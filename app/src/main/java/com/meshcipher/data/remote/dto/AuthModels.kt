package com.meshcipher.data.remote.dto

import com.google.gson.annotations.SerializedName

data class AuthChallengeRequest(
    @SerializedName("userId")
    val userId: String,
    @SerializedName("publicKey")
    val publicKey: String
)

data class AuthChallengeResponse(
    @SerializedName("challenge")
    val challenge: String
)

data class AuthVerifyRequest(
    @SerializedName("userId")
    val userId: String,
    @SerializedName("challenge")
    val challenge: String,
    @SerializedName("signature")
    val signature: String
)

data class AuthVerifyResponse(
    @SerializedName("token")
    val token: String,
    @SerializedName("expires_in")
    val expiresIn: Long
)
