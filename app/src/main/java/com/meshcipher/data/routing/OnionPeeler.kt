package com.meshcipher.data.routing

import com.meshcipher.data.transport.OnionRoutingHeader
import com.meshcipher.data.transport.PeeledLayer
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECPublicKey
import timber.log.Timber
import javax.crypto.Cipher
import javax.crypto.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec

/**
 * MD-04: Peels one layer of an [OnionRoutingHeader] using the node's private key.
 *
 * Each relay or destination calls [peelLayer] when it receives a message with
 * [com.meshcipher.data.transport.RoutingMode.ONION].  On success, it obtains
 * a [PeeledLayer] that reveals:
 *   - [PeeledLayer.nextHopDeviceId] — where to forward the inner onion (empty if terminal)
 *   - [PeeledLayer.innerOnion]     — the next [OnionRoutingHeader] to include in the forwarded message
 *   - [PeeledLayer.isTerminal]     — true when this node is the final destination
 *
 * If decryption fails the message should be silently dropped (do not log the error at
 * INFO+ to avoid timing side-channels).
 */
object OnionPeeler {

    /**
     * Peel one layer from [header] using [myPrivateKeyBytes] (raw 32-byte X25519 private key).
     *
     * @throws IllegalArgumentException if the decrypted layer is malformed.
     */
    fun peelLayer(header: OnionRoutingHeader, myPrivateKeyBytes: ByteArray): PeeledLayer {
        // 1. Reconstruct the sender's ephemeral public key.
        val ephemeralPublicKey: ECPublicKey = Curve.decodePoint(
            // Signal's decodePoint expects a 33-byte DJB-encoded key (type byte 0x05 + 32 bytes).
            byteArrayOf(0x05) + header.ephemeralPublicKey, 0
        )

        // 2. Reconstruct our private key as Signal ECPrivateKey.
        val myPrivateKey = Curve.decodePrivatePoint(myPrivateKeyBytes)

        // 3. ECDH: compute shared secret.
        val sharedSecret = Curve.calculateAgreement(ephemeralPublicKey, myPrivateKey)

        // 4. HKDF-SHA256: derive AES key.
        val aesKey = OnionBuilder.hkdfDerive(
            sharedSecret,
            "meshcipher-routing-v1".toByteArray(Charsets.UTF_8),
            32
        )

        // 5. AES-256-GCM decrypt.
        val encLayer = header.encryptedLayer
        val nonce = encLayer.copyOfRange(0, OnionRoutingHeader.NONCE_SIZE)
        val ciphertextWithTag = encLayer.copyOfRange(OnionRoutingHeader.NONCE_SIZE, encLayer.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
        val paddedPlaintext = cipher.doFinal(ciphertextWithTag)

        // 6. Parse layer plaintext.
        return deserialiseLayerPlaintext(paddedPlaintext)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun deserialiseLayerPlaintext(data: ByteArray): PeeledLayer {
        if (data.isEmpty()) throw IllegalArgumentException("Empty onion layer plaintext")
        var pos = 0

        val isTerminal = data[pos++] == 1.toByte()

        val idLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2
        val nextHopDeviceId = String(data, pos, idLen, Charsets.UTF_8)
        pos += idLen

        val innerLen = ((data[pos].toInt() and 0xFF) shl 24) or
            ((data[pos + 1].toInt() and 0xFF) shl 16) or
            ((data[pos + 2].toInt() and 0xFF) shl 8) or
            (data[pos + 3].toInt() and 0xFF)
        pos += 4

        val innerOnion = if (isTerminal || innerLen == 0) {
            null
        } else {
            val innerBytes = data.copyOfRange(pos, pos + innerLen)
            deserialiseOnion(innerBytes)
        }

        return PeeledLayer(
            nextHopDeviceId = nextHopDeviceId.ifEmpty { null },
            innerOnion = innerOnion,
            isTerminal = isTerminal
        )
    }

    /** Public accessor for [MeshMessage.fromBytes] deserialisation. */
    fun deserialiseOnionPublic(bytes: ByteArray): OnionRoutingHeader = deserialiseOnion(bytes)

    private fun deserialiseOnion(bytes: ByteArray): OnionRoutingHeader {
        var pos = 0
        val pubKeyLen = bytes[pos++].toInt() and 0xFF
        val ephemeralPublicKey = bytes.copyOfRange(pos, pos + pubKeyLen); pos += pubKeyLen
        val encLayerLen = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
        pos += 2
        val encryptedLayer = bytes.copyOfRange(pos, pos + encLayerLen); pos += encLayerLen
        val isTerminal = bytes[pos] == 1.toByte()
        return OnionRoutingHeader(ephemeralPublicKey, encryptedLayer, isTerminal)
    }
}
