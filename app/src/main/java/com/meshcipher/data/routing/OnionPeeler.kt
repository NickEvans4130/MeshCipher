package com.meshcipher.data.routing

import com.meshcipher.data.transport.OnionRoutingHeader
import com.meshcipher.data.transport.PeeledLayer
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECPublicKey
import timber.log.Timber
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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
     * @throws IllegalArgumentException if the decrypted cell is malformed.
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

        // 5. AES-256-GCM decrypt the cell.
        val encCell = header.encryptedLayer
        val nonce = encCell.copyOfRange(0, OnionRoutingHeader.NONCE_SIZE)
        val ciphertextWithTag = encCell.copyOfRange(OnionRoutingHeader.NONCE_SIZE, encCell.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
        val paddedPlaintext = cipher.doFinal(ciphertextWithTag)

        // 6. Parse cell plaintext: isTerminal + nextHopDeviceId.
        val (isTerminal, nextHopDeviceId) = deserialiseCellPlaintext(paddedPlaintext)

        // 7. Reconstruct inner onion from remainingLayerBytes (already encrypted by inner recipients).
        val innerOnion = if (isTerminal || header.remainingLayerBytes.isEmpty()) {
            null
        } else {
            deserialiseOnion(header.remainingLayerBytes)
        }

        return PeeledLayer(
            nextHopDeviceId = nextHopDeviceId.ifEmpty { null },
            innerOnion = innerOnion,
            isTerminal = isTerminal
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun deserialiseCellPlaintext(data: ByteArray): Pair<Boolean, String> {
        if (data.isEmpty()) throw IllegalArgumentException("Empty onion cell plaintext")
        var pos = 0

        val isTerminal = data[pos++] == 1.toByte()

        val idLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2
        val nextHopDeviceId = String(data, pos, idLen, Charsets.UTF_8)

        return isTerminal to nextHopDeviceId
    }

    /** Public accessor for [MeshMessage.fromBytes] deserialisation. */
    fun deserialiseOnionPublic(bytes: ByteArray): OnionRoutingHeader = deserialiseOnion(bytes)

    private fun deserialiseOnion(bytes: ByteArray): OnionRoutingHeader {
        var pos = 0
        val pubKeyLen = bytes[pos++].toInt() and 0xFF
        val ephemeralPublicKey = bytes.copyOfRange(pos, pos + pubKeyLen); pos += pubKeyLen
        val cellLen = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
        pos += 2
        val encryptedLayer = bytes.copyOfRange(pos, pos + cellLen); pos += cellLen
        val remLen = ((bytes[pos].toInt() and 0xFF) shl 24) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
            (bytes[pos + 3].toInt() and 0xFF)
        pos += 4
        val remainingLayerBytes = bytes.copyOfRange(pos, pos + remLen); pos += remLen
        val isTerminal = bytes[pos] == 1.toByte()
        return OnionRoutingHeader(ephemeralPublicKey, encryptedLayer, remainingLayerBytes, isTerminal)
    }
}
