package com.meshcipher.shared.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Contact(
    val id: String,
    val displayName: String,
    val publicKey: ByteArray,
    val identityKey: ByteArray,
    val lastSeen: Long = 0L,
    val onionAddress: String? = null,

    // Safety number tracking
    val currentSafetyNumber: String? = null,
    val verifiedSafetyNumber: String? = null,
    val safetyNumberVerifiedAt: Long? = null,
    val safetyNumberChangedAt: Long? = null
) {
    /**
     * True if the contact has never been verified, or the safety number has
     * changed since last verification.
     */
    fun needsVerification(): Boolean {
        return verifiedSafetyNumber == null || currentSafetyNumber != verifiedSafetyNumber
    }

    /**
     * True if we have a previously verified number AND the current number differs.
     * Indicates a key rotation event (reinstall / backup restore).
     */
    fun safetyNumberChanged(): Boolean {
        return verifiedSafetyNumber != null &&
            currentSafetyNumber != null &&
            currentSafetyNumber != verifiedSafetyNumber
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as Contact
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
