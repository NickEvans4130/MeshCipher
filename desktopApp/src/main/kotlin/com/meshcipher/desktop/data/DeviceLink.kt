package com.meshcipher.desktop.data

import kotlinx.serialization.Serializable

@Serializable
data class DeviceLinkRequest(
    val deviceId: String,
    val deviceName: String,
    val publicKeyHex: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class DeviceLinkResponse(
    val approved: Boolean,
    val deviceId: String,
    val phonePublicKeyHex: String? = null
)
