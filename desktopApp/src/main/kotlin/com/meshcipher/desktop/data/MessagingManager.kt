package com.meshcipher.desktop.data

import com.meshcipher.desktop.crypto.MessageCrypto
import com.meshcipher.desktop.crypto.MessageEnvelope
import com.meshcipher.desktop.network.RelayTransport
import com.meshcipher.desktop.network.normalize
import com.meshcipher.desktop.platform.DesktopPlatform
import com.meshcipher.shared.domain.model.DeviceLinkResponse
import com.meshcipher.shared.util.generateUUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Orchestrates the full send / receive message lifecycle.
 *
 * Handles three incoming message types from the relay:
 *  - contentType=0  → encrypted message → decrypt → emit to [newMessages]
 *  - contentType=10 → device link response → store linked device
 *  - contentType=11 → contact sync payload → upsert contacts, emit [contactsUpdated]
 *
 * Incoming messages from the relay server arrive in the format:
 *   {"type":"new_message","message":{"sender_id":"...","encrypted_content":"<base64>","content_type":N}}
 * which is normalised by [RelayMessage.normalize()] before processing.
 */
class MessagingManager(
    private val localDeviceId: String,
    private val crypto: MessageCrypto,
    private val relay: RelayTransport
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    private val _newMessages = MutableSharedFlow<DesktopMessage>(extraBufferCapacity = 64)
    val newMessages: SharedFlow<DesktopMessage> = _newMessages.asSharedFlow()

    /** Emits Unit whenever the contacts list changes (after a sync). */
    private val _contactsUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val contactsUpdated: SharedFlow<Unit> = _contactsUpdated.asSharedFlow()

    init {
        scope.launch { listenForIncoming() }
    }

    suspend fun sendMessage(content: String, recipientId: String): Result<DesktopMessage> =
        withContext(Dispatchers.IO) {
            val contact = ContactRepository.getContact(recipientId)
                ?: return@withContext Result.failure(IllegalStateException("Contact $recipientId not found"))

            val msgId = generateUUID()
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
            val normalized = relayMsg.normalize() ?: return@collect
            runCatching { dispatch(normalized) }
        }
    }

    private suspend fun dispatch(msg: com.meshcipher.desktop.network.NormalizedMessage) {
        // Payload from relay server is base64-encoded; desktop→desktop is raw JSON
        val payloadBytes = runCatching {
            Base64.getDecoder().decode(msg.rawPayload)
        }.getOrElse { msg.rawPayload.toByteArray() }

        when (msg.contentType) {
            CONTENT_TYPE_DEVICE_LINK -> handleDeviceLinkResponse(payloadBytes)
            CONTENT_TYPE_CONTACT_SYNC -> handleContactSync(payloadBytes)
            else -> handleMessage(msg.senderId, payloadBytes, msg.rawPayload)
        }
    }

    private fun handleDeviceLinkResponse(bytes: ByteArray) {
        val response = runCatching {
            json.decodeFromString<DeviceLinkResponse>(String(bytes))
        }.getOrNull() ?: return
        scope.launch { DeviceLinkManager.processLinkResponse(response) }
    }

    private suspend fun handleContactSync(bytes: ByteArray) {
        val payload = runCatching {
            json.decodeFromString<ContactSyncPayload>(String(bytes))
        }.getOrNull() ?: return

        for (item in payload.contacts) {
            ContactRepository.upsertContact(
                contactId = item.userId,
                displayName = item.displayName,
                publicKeyHex = item.publicKeyHex
            )
        }
        _contactsUpdated.emit(Unit)
    }

    private suspend fun handleMessage(senderId: String, payloadBytes: ByteArray, @Suppress("UNUSED_PARAMETER") rawPayload: String) {
        val contact = ContactRepository.getContact(senderId) ?: return

        // Try as MessageEnvelope (ECDH-encrypted, desktop→desktop)
        val payloadJson = String(payloadBytes)
        val envelope = runCatching { MessageEnvelope.fromJson(payloadJson) }.getOrNull()

        val plaintext = if (envelope != null) {
            runCatching { crypto.decryptMessage(envelope, contact.publicKeyHex) }.getOrNull()
                ?: return // Decryption failed — wrong key or corrupted
        } else {
            // Phone forwarding sends plaintext bytes; treat as UTF-8 text directly
            payloadJson.trim().ifBlank { return }
        }

        val msg = MessageRepository.save(
            contactId = senderId,
            content = plaintext,
            isOutgoing = false,
            status = "delivered",
            timestamp = envelope?.timestamp ?: System.currentTimeMillis()
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
