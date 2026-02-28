package com.meshcipher.desktop.crypto

import com.meshcipher.shared.crypto.KeyManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Structured message envelope carried over the relay WebSocket.
 * The [body] is AES-256-GCM ciphertext (12-byte IV prepended).
 * [type] is reserved for future Signal Protocol message type codes.
 */
@Serializable
data class MessageEnvelope(
    val type: Int,
    val body: ByteArray,
    val senderUserId: String,
    val recipientUserId: String,
    val timestamp: Long
) {
    fun toJson(): String = envelopeJson.encodeToString(this)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEnvelope) return false
        return type == other.type &&
            body.contentEquals(other.body) &&
            senderUserId == other.senderUserId &&
            recipientUserId == other.recipientUserId
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + body.contentHashCode()
        result = 31 * result + senderUserId.hashCode()
        result = 31 * result + recipientUserId.hashCode()
        return result
    }

    companion object {
        fun fromJson(json: String): MessageEnvelope = envelopeJson.decodeFromString(json)

        // TYPE_AES_GCM: symmetric AES-256-GCM with ECDH-derived session key.
        // Future: TYPE_PREKEY (Signal X3DH), TYPE_WHISPER (Signal double ratchet).
        const val TYPE_AES_GCM = 0
    }
}

private val envelopeJson = Json { ignoreUnknownKeys = true }

/**
 * Message encryption / decryption for the desktop app.
 *
 * Session key derivation: ECDH(our_priv, their_pub) → SHA-256 → 32-byte AES key.
 * Both sides derive the same key, so any message encrypted with this key can be
 * decrypted by the other party using their own ECDH call with our public key.
 *
 * Limitation: no forward secrecy. Full Signal Protocol X3DH is the planned upgrade.
 * For desktop ↔ phone messaging, a key-exchange handshake over the device link
 * channel is required (future work).
 */
class MessageCrypto(
    private val localUserId: String,
    private val keyManager: KeyManager
) {
    // Per-contact session keys cached in memory (ECDH-derived or pre-established)
    private val sessionKeys = mutableMapOf<String, ByteArray>()

    /**
     * Encrypt [plaintext] for [recipientUserId].
     * [recipientPublicKeyHex] is the hex-encoded EC P-256 public key of the recipient.
     */
    fun encryptMessage(
        plaintext: String,
        recipientUserId: String,
        recipientPublicKeyHex: String
    ): MessageEnvelope {
        val sessionKey = getOrDeriveSessionKey(recipientUserId, recipientPublicKeyHex)
        val body = aesGcmEncrypt(plaintext.toByteArray(Charsets.UTF_8), sessionKey)
        return MessageEnvelope(
            type = MessageEnvelope.TYPE_AES_GCM,
            body = body,
            senderUserId = localUserId,
            recipientUserId = recipientUserId,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Decrypt a [MessageEnvelope] received from [envelope.senderUserId].
     * [senderPublicKeyHex] must be the sender's registered EC public key.
     */
    fun decryptMessage(envelope: MessageEnvelope, senderPublicKeyHex: String): String {
        val sessionKey = getOrDeriveSessionKey(envelope.senderUserId, senderPublicKeyHex)
        return aesGcmDecrypt(envelope.body, sessionKey).toString(Charsets.UTF_8)
    }

    /**
     * Store an externally-negotiated session key (e.g. from Signal Protocol handshake).
     */
    fun storeSessionKey(userId: String, keyBytes: ByteArray) {
        sessionKeys[userId] = keyBytes
    }

    private fun getOrDeriveSessionKey(userId: String, publicKeyHex: String): ByteArray {
        sessionKeys[userId]?.let { return it }
        val theirPublicKeyBytes = fromHex(publicKeyHex)
        val derived = keyManager.performECDH(theirPublicKeyBytes)
        sessionKeys[userId] = derived
        return derived
    }

    private fun aesGcmEncrypt(plaintext: ByteArray, keyBytes: ByteArray): ByteArray {
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, SecureRandom())
        val iv = cipher.iv
        return iv + cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(ivAndCiphertext: ByteArray, keyBytes: ByteArray): ByteArray {
        val key = SecretKeySpec(keyBytes, "AES")
        val iv = ivAndCiphertext.copyOf(12)
        val ciphertext = ivAndCiphertext.copyOfRange(12, ivAndCiphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
        fun fromHex(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
