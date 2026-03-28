package com.meshcipher.data.transport

/**
 * MD-04: Source-routing onion header.
 *
 * Each relay node receives an [OnionRoutingHeader].  It performs ECDH with the
 * [ephemeralPublicKey] using its own long-term private key, derives a symmetric
 * key via HKDF-SHA256, and decrypts [encryptedLayer] to reveal the next-hop
 * device ID and whether this node is the terminal destination.
 *
 * The remaining hop layers are carried in [remainingLayerBytes] (already
 * encrypted by their respective recipient keys) and forwarded untouched.
 *
 * [encryptedLayer] is always [ONION_LAYER_SIZE] bytes:
 *   12-byte nonce | AES-256-GCM ciphertext ([ONION_PLAINTEXT_CAPACITY] bytes) | 16-byte tag
 *
 * [isTerminal] is set on the outermost header to allow quick filtering without
 * decryption; the authoritative value comes from the decrypted cell content.
 */
data class OnionRoutingHeader(
    val ephemeralPublicKey: ByteArray,   // 32 bytes, X25519
    val encryptedLayer: ByteArray,       // ONION_LAYER_SIZE bytes fixed
    val remainingLayerBytes: ByteArray,  // serialised inner OnionRoutingHeaders (variable)
    val isTerminal: Boolean              // authoritative after peeling; hint on outer header
) {
    companion object {
        // Cell size: nonce(12) + plaintext_capacity(100) + tag(16) = 128.
        // Plaintext capacity of 100 bytes is sufficient for isTerminal(1) + idLen(2) + deviceId(≤97).
        const val ONION_LAYER_SIZE = 128
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16
        const val ONION_PLAINTEXT_CAPACITY = ONION_LAYER_SIZE - NONCE_SIZE - TAG_SIZE  // 100
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as OnionRoutingHeader
        return ephemeralPublicKey.contentEquals(other.ephemeralPublicKey) &&
            encryptedLayer.contentEquals(other.encryptedLayer) &&
            remainingLayerBytes.contentEquals(other.remainingLayerBytes) &&
            isTerminal == other.isTerminal
    }

    override fun hashCode(): Int {
        var result = ephemeralPublicKey.contentHashCode()
        result = 31 * result + encryptedLayer.contentHashCode()
        result = 31 * result + remainingLayerBytes.contentHashCode()
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
