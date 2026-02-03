package com.meshcipher.domain.model

data class Identity(
    val userId: String,
    val hardwarePublicKey: ByteArray,
    val createdAt: Long,
    val deviceId: String,
    val deviceName: String,
    val recoverySetup: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Identity
        return userId == other.userId
    }

    override fun hashCode(): Int = userId.hashCode()
}
