package com.meshcipher.data.remote.dto

import com.google.gson.annotations.SerializedName

/** Request to consume a one-time QR nonce (GAP-05 / R-06). */
data class ConsumeNonceRequest(
    @SerializedName("nonce")
    val nonce: String,
    @SerializedName("device_id")
    val deviceId: String
)

data class ConsumeNonceResponse(
    @SerializedName("status")
    val status: String
)
