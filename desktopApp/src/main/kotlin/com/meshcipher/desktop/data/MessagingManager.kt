package com.meshcipher.desktop.data

import com.meshcipher.desktop.crypto.MessageCrypto
import com.meshcipher.desktop.crypto.MessageEnvelope
import com.meshcipher.desktop.network.RelayTransport
import com.meshcipher.desktop.platform.DesktopPlatform
import com.meshcipher.shared.util.generateUUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Orchestrates the full send / receive message lifecycle:
 *
 * Send:  UI → [sendMessage] → encrypt → relay WebSocket → phone/peer
 * Receive: relay WebSocket → [handleIncoming] → decrypt → DB → [newMessages] Flow → UI
 *
 * Note on desktop ↔ phone interoperability: the Android app uses Signal Protocol
 * (libsignal-android) for encryption. Until a full X3DH handshake is implemented
 * for the desktop, messaging only works between two desktop clients that share the
 * same ECDH-derived session key. For desktop ↔ phone, the device-link handshake
 * channel (Phase 12+) will negotiate a shared session key.
 */
class MessagingManager(
    private val localDeviceId: String,
    private val crypto: MessageCrypto,
    private val relay: RelayTransport
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _newMessages = MutableSharedFlow<DesktopMessage>(extraBufferCapacity = 64)
    /** Emits messages as they arrive from the relay. Observe from the UI. */
    val newMessages: SharedFlow<DesktopMessage> = _newMessages.asSharedFlow()

    init {
        scope.launch { listenForIncoming() }
    }

    /**
     * Encrypt and send [content] to [recipientId].
     * The recipient's EC public key is read from the local contact database.
     * Returns the persisted [DesktopMessage] on success.
     */
    suspend fun sendMessage(
        content: String,
        recipientId: String
    ): Result<DesktopMessage> = withContext(Dispatchers.IO) {
        val contact = ContactRepository.getContact(recipientId)
            ?: return@withContext Result.failure(IllegalStateException("Contact $recipientId not found"))

        val msgId = generateUUID()
        // Persist with SENDING status before network round-trip
        val localMsg = MessageRepository.save(
            contactId = recipientId,
            content = content,
            isOutgoing = true,
            status = "sending",
            messageId = msgId
        )

        runCatching {
            val envelope = crypto.encryptMessage(
                plaintext = content,
                recipientUserId = recipientId,
                recipientPublicKeyHex = contact.publicKeyHex
            )
            relay.sendMessage(recipientId, envelope.toJson())
            MessageRepository.updateStatus(msgId, "sent")
            localMsg.copy(status = "sent")
        }.also { result ->
            if (result.isFailure) MessageRepository.updateStatus(msgId, "failed")
        }
    }

    private suspend fun listenForIncoming() {
        relay.incomingMessages.collect { relayMsg ->
            if (relayMsg.type != "new_message" && relayMsg.type != "message") return@collect
            runCatching { handleIncoming(relayMsg.senderId, relayMsg.payload) }
        }
    }

    private suspend fun handleIncoming(senderId: String, payloadJson: String) {
        val contact = ContactRepository.getContact(senderId) ?: return
        val envelope = runCatching { MessageEnvelope.fromJson(payloadJson) }.getOrNull() ?: return

        val plaintext = runCatching {
            crypto.decryptMessage(envelope, contact.publicKeyHex)
        }.getOrElse { return } // decrypt failed — wrong session key or corrupt data

        val msg = MessageRepository.save(
            contactId = senderId,
            content = plaintext,
            isOutgoing = false,
            status = "delivered",
            timestamp = envelope.timestamp
        )
        _newMessages.emit(msg)

        val senderName = contact.displayName.ifBlank { senderId.take(8) }
        DesktopPlatform.showNotification(
            title = senderName,
            body = plaintext.take(80).let { if (plaintext.length > 80) "$it…" else it }
        )
    }

    fun dispose() = scope.cancel()
}
