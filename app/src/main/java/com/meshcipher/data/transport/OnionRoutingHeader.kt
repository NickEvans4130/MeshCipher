package com.meshcipher.data.transport

/**
 * MD-04: Source-routing onion header.
 *
 * Each relay node receives an [OnionRoutingHeader].  It performs ECDH with the
 * [ephemeralPublicKey] using its own long-term private key, derives a symmetric
 * key via HKDF-SHA256, and decrypts [encryptedLayer] to reveal the next-hop
 * device ID and the inner [OnionRoutingHeader] to forward.
 *
 * [encryptedLayer] is always [ONION_LAYER_SIZE] bytes:
 *   12-byte nonce | AES-256-GCM ciphertext (ONION_PLAINTEXT_CAPACITY bytes) | 16-byte tag
 *
 * [isTerminal] is `false` in transit; it is derived from the decrypted layer
 * content by [com.meshcipher.data.routing.OnionPeeler] and surfaced in [PeeledLayer].
 */
data class OnionRoutingHeader(
    val ephemeralPublicKey: ByteArray,   // 32 bytes, X25519
    val encryptedLayer: ByteArray,       // ONION_LAYER_SIZE bytes fixed
    val isTerminal: Boolean              // false in transit; true at destination after peeling
) {
    companion object {
        // Total encrypted layer size: nonce(12) + plaintext_capacity(484) + tag(16) = 512.
        const val ONION_LAYER_SIZE = 512
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16
        const val ONION_PLAINTEXT_CAPACITY = ONION_LAYER_SIZE - NONCE_SIZE - TAG_SIZE  // 484
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as OnionRoutingHeader
        return ephemeralPublicKey.contentEquals(other.ephemeralPublicKey) &&
            encryptedLayer.contentEquals(other.encryptedLayer) &&
            isTerminal == other.isTerminal
    }

    override fun hashCode(): Int {
        var result = ephemeralPublicKey.contentHashCode()
        result = 31 * result + encryptedLayer.contentHashCode()
        result = 31 * result + isTerminal.hashCode()
        return result
    }
}

/** Result of peeling one onion layer. */
data class PeeledLayer(
    val nextHopDeviceId: String?,
    val innerOnion: OnionRoutingHeader?,
    val isTerminal: Boolean
)

/**
 * Routing mode tag on [com.meshcipher.domain.model.MeshMessage].
 * PLAINTEXT — existing header behaviour (unmodified).
 * ONION     — [OnionRoutingHeader] is present; relay/destination must peel.
 */
enum class RoutingMode { PLAINTEXT, ONION }
