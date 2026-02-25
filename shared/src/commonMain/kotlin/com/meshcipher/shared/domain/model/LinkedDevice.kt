package com.meshcipher.shared.domain.model

import kotlinx.serialization.Serializable

enum class DeviceType { ANDROID, DESKTOP, IOS, UNKNOWN }

@Serializable
data class LinkedDevice(
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType = DeviceType.UNKNOWN,
    val publicKeyHex: String,
    val linkedAt: Long,
    val approved: Boolean = false
)

/**
 * Payload encoded in the QR code the desktop displays.
 * URL format: meshcipher://link/<base64url-encoded-JSON>
 */
@Serializable
data class DeviceLinkRequest(
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType = DeviceType.DESKTOP,
    val publicKeyHex: String,
    val timestamp: Long
)

/**
 * Response sent from the phone back to the desktop via the relay.
 */
@Serializable
data class DeviceLinkResponse(
    val approved: Boolean,
    val phoneDeviceId: String,
    val phoneUserId: String,
    val phonePublicKeyHex: String? = null
)
