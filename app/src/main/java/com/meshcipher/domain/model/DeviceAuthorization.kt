package com.meshcipher.domain.model

data class DeviceAuthorization(
    val deviceId: String,
    val publicKey: ByteArray,
    val authorizedBy: String,
    val authorizedAt: Long,
    val expiresAt: Long,
    val revoked: Boolean = false,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeviceAuthorization
        return deviceId == other.deviceId
    }

    override fun hashCode(): Int = deviceId.hashCode()
}
