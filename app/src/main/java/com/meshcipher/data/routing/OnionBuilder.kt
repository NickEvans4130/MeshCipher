package com.meshcipher.data.routing

import com.meshcipher.data.transport.OnionRoutingHeader
import com.meshcipher.domain.model.MeshPeer
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECPublicKey
import timber.log.Timber
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom

/**
 * MD-04: Builds a source-routing onion over the mesh routing header.
 *
 * The onion is constructed from the inside out — the terminal (destination) layer
 * is built first, then each relay layer wraps the previous.  The outermost layer is
 * returned and transmitted with the message.
 *
 * Cryptographic primitives (no new libraries):
 *   - X25519 key agreement via libsignal's [Curve.calculateAgreement]
 *   - HKDF-SHA256 (manual extract+expand) via [javax.crypto.Mac]
 *   - AES-256-GCM via [javax.crypto.Cipher]
 */
object OnionBuilder {

    private val secureRandom = SecureRandom()

    // HKDF info string as per spec.
    private val HKDF_INFO = "meshcipher-routing-v1".toByteArray(Charsets.UTF_8)

    /**
     * Build an onion for [route] → [destination].
     *
     * @param route      Ordered list of relay hops (up to [MAX_HOPS]). May be empty for
     *                   direct delivery — returns a single terminal layer.
     * @param destination The final recipient peer.
     * @param identityKeyStore Signal Protocol identity store used to look up hop public keys.
     * @return The outermost [OnionRoutingHeader] ready for transmission, or null if any
     *         hop's identity key is missing (caller should fall back to PLAINTEXT routing).
     */
    fun buildOnion(
        route: List<MeshPeer>,
        destination: MeshPeer,
        identityKeyStore: IdentityKeyStore
    ): OnionRoutingHeader? {
        if (route.size > MAX_HOPS) {
            Timber.d("MD-04: route length ${route.size} exceeds MAX_HOPS $MAX_HOPS, truncating")
        }
        val hops = route.take(MAX_HOPS)

        // Build terminal layer for the destination.
        val destKey = identityKeyStore.getIdentity(
            SignalProtocolAddress(destination.userId, 1)
        ) ?: run {
            Timber.d("MD-04: missing identity key for destination %s, falling back", destination.userId)
            return null
        }

        var currentOnion = buildLayer(
            nextHopDeviceId = "",
            innerOnionBytes = ByteArray(0),
            isTerminal = true,
            recipientPublicKey = destKey.publicKey
        ) ?: return null

        // Wrap relay hops from innermost to outermost.
        for (hop in hops.reversed()) {
            val hopKey = identityKeyStore.getIdentity(
                SignalProtocolAddress(hop.userId, 1)
            ) ?: run {
                Timber.d("MD-04: missing identity key for hop %s, falling back", hop.userId)
                return null
            }
            val innerBytes = serialiseOnion(currentOnion)
            currentOnion = buildLayer(
                nextHopDeviceId = hops.getOrNull(hops.indexOf(hop) + 1)?.deviceId
                    ?: destination.deviceId,
                innerOnionBytes = innerBytes,
                isTerminal = false,
                recipientPublicKey = hopKey.publicKey
            ) ?: return null
        }
        return currentOnion
    }

    /**
     * Encrypt a single onion layer for [recipientPublicKey].
     * Returns null if encryption fails.
     */
    fun buildLayer(
        nextHopDeviceId: String,
        innerOnionBytes: ByteArray,
        isTerminal: Boolean,
        recipientPublicKey: ECPublicKey
    ): OnionRoutingHeader? {
        return try {
            // 1. Generate ephemeral X25519 keypair.
            val ephemeralKeyPair = Curve.generateKeyPair()
            val ephemeralPublicBytes = ephemeralKeyPair.publicKey.serialize().let { serialised ->
                // Signal serialises ECPublicKey with a 1-byte type prefix; strip it.
                if (serialised.size == 33) serialised.copyOfRange(1, 33) else serialised
            }

            // 2. ECDH: shared secret = X25519(ourPrivate, theirPublic).
            val sharedSecret = Curve.calculateAgreement(
                recipientPublicKey,
                ephemeralKeyPair.privateKey
            )

            // 3. HKDF-SHA256: derive 32-byte AES key.
            val aesKey = hkdfDerive(sharedSecret, HKDF_INFO, 32)

            // 4. Serialise plaintext layer and pad to ONION_PLAINTEXT_CAPACITY.
            val plaintext = serialiseLayerPlaintext(nextHopDeviceId, innerOnionBytes, isTerminal)
            val paddedPlaintext = padToCapacity(plaintext, OnionRoutingHeader.ONION_PLAINTEXT_CAPACITY)

            // 5. AES-256-GCM encrypt.
            val nonce = ByteArray(OnionRoutingHeader.NONCE_SIZE).also { secureRandom.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
            val ciphertextWithTag = cipher.doFinal(paddedPlaintext)

            // 6. Assemble encryptedLayer: nonce || ciphertext+tag = 512 bytes.
            val encryptedLayer = ByteArray(OnionRoutingHeader.ONION_LAYER_SIZE)
            System.arraycopy(nonce, 0, encryptedLayer, 0, OnionRoutingHeader.NONCE_SIZE)
            System.arraycopy(ciphertextWithTag, 0, encryptedLayer,
                OnionRoutingHeader.NONCE_SIZE, ciphertextWithTag.size)

            OnionRoutingHeader(
                ephemeralPublicKey = ephemeralPublicBytes,
                encryptedLayer = encryptedLayer,
                isTerminal = isTerminal
            )
        } catch (e: Exception) {
            Timber.e(e, "MD-04: failed to build onion layer")
            null
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Serialise a layer's plaintext as length-prefixed fields. */
    private fun serialiseLayerPlaintext(
        nextHopDeviceId: String,
        innerOnionBytes: ByteArray,
        isTerminal: Boolean
    ): ByteArray {
        val idBytes = nextHopDeviceId.toByteArray(Charsets.UTF_8)
        // Format: [isTerminal:1][idLen:2][id][innerLen:4][inner]
        val buf = ByteArray(1 + 2 + idBytes.size + 4 + innerOnionBytes.size)
        var pos = 0
        buf[pos++] = if (isTerminal) 1 else 0
        buf[pos++] = (idBytes.size shr 8).toByte()
        buf[pos++] = (idBytes.size and 0xFF).toByte()
        System.arraycopy(idBytes, 0, buf, pos, idBytes.size); pos += idBytes.size
        buf[pos++] = (innerOnionBytes.size shr 24).toByte()
        buf[pos++] = (innerOnionBytes.size shr 16 and 0xFF).toByte()
        buf[pos++] = (innerOnionBytes.size shr 8 and 0xFF).toByte()
        buf[pos++] = (innerOnionBytes.size and 0xFF).toByte()
        System.arraycopy(innerOnionBytes, 0, buf, pos, innerOnionBytes.size)
        return buf
    }

    /** Serialise an [OnionRoutingHeader] to bytes for embedding in an outer layer. */
    fun serialiseOnion(header: OnionRoutingHeader): ByteArray {
        // Format: [pubKeyLen:1][pubKey][encLayerLen:2][encLayer][isTerminal:1]
        val pubKey = header.ephemeralPublicKey
        val encLayer = header.encryptedLayer
        val buf = ByteArray(1 + pubKey.size + 2 + encLayer.size + 1)
        var pos = 0
        buf[pos++] = pubKey.size.toByte()
        System.arraycopy(pubKey, 0, buf, pos, pubKey.size); pos += pubKey.size
        buf[pos++] = (encLayer.size shr 8).toByte()
        buf[pos++] = (encLayer.size and 0xFF).toByte()
        System.arraycopy(encLayer, 0, buf, pos, encLayer.size); pos += encLayer.size
        buf[pos] = if (header.isTerminal) 1 else 0
        return buf
    }

    /** Pad [data] with zero bytes to exactly [capacity] bytes. Truncates if larger. */
    private fun padToCapacity(data: ByteArray, capacity: Int): ByteArray {
        if (data.size >= capacity) return data.copyOf(capacity)
        return data.copyOf(capacity)  // copyOf pads with zeros automatically
    }

    /** HKDF-SHA256: extract then expand [ikm] with [info] to produce [length] bytes. */
    internal fun hkdfDerive(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        // Extract: PRK = HMAC-SHA256(salt=zeroes, IKM)
        val zeroSalt = ByteArray(32)
        val prk = hmacSha256(zeroSalt, ikm)
        // Expand: T(1) = HMAC-SHA256(PRK, info || 0x01)
        val expand = hmacSha256(prk, info + byteArrayOf(0x01))
        return expand.copyOf(length)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    companion object {
        const val MAX_HOPS = 5
    }
}
