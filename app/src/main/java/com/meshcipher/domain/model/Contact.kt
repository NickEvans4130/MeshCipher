package com.meshcipher.domain.model

import org.signal.libsignal.protocol.SignalProtocolAddress

data class Contact(
    val id: String,
    val displayName: String,
    val publicKey: ByteArray,
    val identityKey: ByteArray,
    val signalProtocolAddress: SignalProtocolAddress,
    val lastSeen: Long = System.currentTimeMillis(),
    val onionAddress: String? = null,

    // Safety number tracking
    val currentSafetyNumber: String? = null,
    val verifiedSafetyNumber: String? = null,
    val safetyNumberVerifiedAt: Long? = null,
    val safetyNumberChangedAt: Long? = null
) {
    /**
     * True if the user has never verified this contact, or if the safety number
     * changed since the last verification.
     */
    fun needsVerification(): Boolean {
        return verifiedSafetyNumber == null || currentSafetyNumber != verifiedSafetyNumber
    }

    /**
     * True if we have a previously verified number AND the current number differs.
     * This indicates a key rotation event (e.g. contact reinstalled from backup).
     */
    fun safetyNumberChanged(): Boolean {
        return verifiedSafetyNumber != null &&
            currentSafetyNumber != null &&
            currentSafetyNumber != verifiedSafetyNumber
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Contact
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
