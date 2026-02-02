package com.meshcipher.domain.model

import org.signal.libsignal.protocol.SignalProtocolAddress

data class Contact(
    val id: String,
    val displayName: String,
    val publicKey: ByteArray,
    val identityKey: ByteArray,
    val signalProtocolAddress: SignalProtocolAddress,
    val lastSeen: Long = System.currentTimeMillis()
) {
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
