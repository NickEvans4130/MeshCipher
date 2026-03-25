package com.meshcipher.domain.usecase

import android.content.Context
import android.util.Base64
import com.meshcipher.data.media.MediaEncryptor
import com.meshcipher.data.media.MediaFileManager
import com.meshcipher.data.linking.LinkConfirmationChannel
import com.meshcipher.data.remote.dto.QueuedMessage
import com.meshcipher.data.service.MessageForwardingService
import com.meshcipher.data.transport.ContentTypes
import com.meshcipher.data.transport.TransportManager
import com.meshcipher.domain.model.MediaAttachment
import com.meshcipher.domain.model.MediaMessageEnvelope
import com.meshcipher.domain.model.MediaType
import com.meshcipher.domain.model.Message
import com.meshcipher.domain.model.MessageStatus
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.repository.ConversationRepository
import com.meshcipher.domain.repository.MessageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

class ReceiveMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val contactRepository: ContactRepository,
    private val transportManager: TransportManager,
    private val mediaEncryptor: MediaEncryptor,
    private val mediaFileManager: MediaFileManager,
    private val forwardingService: MessageForwardingService,
    private val sendMessageUseCase: SendMessageUseCase,
    private val sendMediaMessageUseCase: SendMediaMessageUseCase,
    private val linkConfirmationChannel: LinkConfirmationChannel,
    @ApplicationContext private val context: Context
) {

    suspend operator fun invoke(localDeviceId: String): Result<Int> {
        val receiveResult = transportManager.getActiveTransport().receiveMessages(localDeviceId)

        if (receiveResult.isFailure) {
            return Result.failure(receiveResult.exceptionOrNull() ?: Exception("Receive failed"))
        }

        val queuedMessages = receiveResult.getOrDefault(emptyList())
        if (queuedMessages.isEmpty()) {
            return Result.success(0)
        }

        var processedCount = 0
        val ackIds = mutableListOf<String>()

        for (queued in queuedMessages) {
            try {
                processQueuedMessage(queued)
                ackIds.add(queued.id)
                processedCount++
            } catch (e: Exception) {
                Timber.e(e, "Failed to process message: %s", queued.id)
            }
        }

        // Acknowledge processed messages
        if (ackIds.isNotEmpty()) {
            transportManager.getActiveTransport().acknowledgeMessages(localDeviceId, ackIds)
        }

        Timber.d("Processed %d/%d messages", processedCount, queuedMessages.size)
        return Result.success(processedCount)
    }

    /**
     * Process a single message received via WebSocket push and acknowledge it.
     * Skips the HTTP fetch since the message data is already available.
     */
    suspend fun processAndAcknowledge(queued: QueuedMessage, localDeviceId: String): Result<Unit> {
        return try {
            processQueuedMessage(queued)
            transportManager.getActiveTransport().acknowledgeMessages(localDeviceId, listOf(queued.id))
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to process pushed message: %s", queued.id)
            Result.failure(e)
        }
    }

    private suspend fun processQueuedMessage(queued: QueuedMessage) {
        when (queued.contentType) {
            ContentTypes.MEDIA -> processMediaMessage(queued)
            ContentTypes.DEVICE_UNLINK -> processUnlinkNotification(queued)
            ContentTypes.DESKTOP_SEND_REQUEST -> processDesktopSendRequest(queued)
            ContentTypes.DESKTOP_MEDIA_REQUEST -> processDesktopMediaRequest(queued)
            // GAP-06 / R-06: Desktop sent its decision on the link confirmation request
            ContentTypes.LINK_CONFIRMED -> processLinkConfirmed(queued)
            ContentTypes.LINK_DENIED -> processLinkDenied(queued)
            ContentTypes.ONION_ADDRESS_UPDATE -> handleOnionAddressUpdate(queued)
            else -> processTextMessage(queued)
        }
    }

    /**
     * Desktop confirmed the link — broadcast so DeviceLinkApprovalViewModel can finalise.
     * The payload contains the requesting deviceId so we know which pending link was confirmed.
     */
    private fun processLinkConfirmed(queued: QueuedMessage) {
        val deviceId = extractDeviceIdFromPayload(queued) ?: return
        linkConfirmationChannel.emit(LinkConfirmationChannel.Event.Confirmed(deviceId))
        Timber.d("Desktop confirmed link for device %s", deviceId)
    }

    private fun processLinkDenied(queued: QueuedMessage) {
        val deviceId = extractDeviceIdFromPayload(queued) ?: return
        linkConfirmationChannel.emit(LinkConfirmationChannel.Event.Denied(deviceId))
        Timber.d("Desktop denied link for device %s", deviceId)
    }

    private fun extractDeviceIdFromPayload(queued: QueuedMessage): String? {
        return try {
            val bytes = android.util.Base64.decode(queued.encryptedContent, android.util.Base64.NO_WRAP)
            val json = org.json.JSONObject(String(bytes))
            json.optString("deviceId").ifBlank { null }
        } catch (e: Exception) {
            Timber.w(e, "Could not extract deviceId from link confirmation payload")
            null
        }
    }

    /**
     * GAP-10 / R-10: Handle an ephemeral onion address update from a contact.
     * Verifies the sender is a known contact and updates their stored onion address.
     */
    private suspend fun handleOnionAddressUpdate(queued: QueuedMessage) {
        try {
            val payloadStr = String(android.util.Base64.decode(queued.encryptedContent, android.util.Base64.NO_WRAP))
            val json = org.json.JSONObject(payloadStr)
            val newOnionAddress = if (json.has("onionAddress")) json.getString("onionAddress") else return
            val senderId = queued.senderId

            // Only update address for known, trusted contacts
            val contact = contactRepository.getContact(senderId) ?: run {
                Timber.w("OnionAddressUpdate from unknown sender %s — ignored", senderId)
                return
            }

            if (!newOnionAddress.endsWith(".onion")) {
                Timber.w("OnionAddressUpdate: invalid address format — ignored")
                return
            }

            contactRepository.updateContact(contact.copy(onionAddress = newOnionAddress))
            Timber.d("Updated onion address for %s to %s", contact.displayName, newOnionAddress)
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle OnionAddressUpdate")
        }
    }

    private suspend fun processUnlinkNotification(queued: QueuedMessage) {
        forwardingService.revokeLinkedDeviceByUserId(queued.senderId)
        Timber.d("Received unlink notification from %s", queued.senderId)
    }

    private suspend fun processTextMessage(queued: QueuedMessage) {
        // Decode content - currently plaintext base64, will be encrypted in later phase
        val contentBytes = Base64.decode(queued.encryptedContent, Base64.NO_WRAP)
        val content = String(contentBytes)

        // Find the contact by their signal protocol address name
        val contacts = contactRepository.getAllContacts().first()
        val senderContact = contacts.find {
            it.id == queued.senderId
        }

        if (senderContact == null) {
            Timber.w("Received message from unknown sender: %s", queued.senderId)
            return
        }

        // Get or create conversation
        val conversationId = conversationRepository.createOrGetConversation(senderContact.id)

        // Save message
        val message = Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            senderId = senderContact.id,
            recipientId = "me",
            content = content,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.DELIVERED,
            isOwnMessage = false
        )

        messageRepository.insertMessage(message)
        conversationRepository.incrementUnreadCount(conversationId)
        forwardingService.forwardIncomingToLinkedDevices(content, senderContact.id, senderContact.displayName)

        Timber.d("Received message from %s in conversation %s",
            senderContact.displayName, conversationId)
    }

    private suspend fun processMediaMessage(queued: QueuedMessage) {
        val contentBytes = Base64.decode(queued.encryptedContent, Base64.NO_WRAP)
        val envelopeJson = String(contentBytes)
        val envelope = MediaMessageEnvelope.fromJson(envelopeJson)

        val contacts = contactRepository.getAllContacts().first()
        val senderContact = contacts.find {
            it.id == queued.senderId
        }

        if (senderContact == null) {
            Timber.w("Received media from unknown sender: %s", queued.senderId)
            return
        }

        val encryptedBytes = java.util.Base64.getDecoder().decode(envelope.encryptedData)
        val decryptedBytes = mediaEncryptor.decrypt(
            encryptedBytes, envelope.encryptionKey, envelope.encryptionIv
        )

        val mediaType = MediaType.valueOf(envelope.mediaType)
        val localPath = mediaFileManager.saveMedia(
            context, envelope.mediaId, decryptedBytes, mediaType
        )

        val attachment = MediaAttachment(
            mediaId = envelope.mediaId,
            mediaType = mediaType,
            fileName = envelope.fileName,
            fileSize = envelope.fileSize,
            mimeType = envelope.mimeType,
            localPath = localPath,
            durationMs = envelope.durationMs,
            width = envelope.width,
            height = envelope.height,
            encryptionKey = envelope.encryptionKey,
            encryptionIv = envelope.encryptionIv
        )

        val conversationId = conversationRepository.createOrGetConversation(senderContact.id)

        val message = Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            senderId = senderContact.id,
            recipientId = "me",
            content = "[${mediaType.name}]",
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.DELIVERED,
            isOwnMessage = false,
            mediaAttachment = attachment
        )

        messageRepository.insertMessage(message)
        conversationRepository.incrementUnreadCount(conversationId)
        forwardingService.forwardMediaToLinkedDevices(
            fileName = envelope.fileName,
            mimeType = envelope.mimeType,
            fileBytes = decryptedBytes,
            contactId = senderContact.id,
            contactName = senderContact.displayName,
            isOutgoing = false
        )

        Timber.d("Received media (%s) from %s in conversation %s",
            mediaType, senderContact.displayName, conversationId)
    }

    /**
     * Desktop sent a message on behalf of the phone user (content_type=16).
     * Payload JSON: {"content":"...","recipientId":"...","senderId":"...","conversationId":"..."}
     * Phone sends it to the actual contact, then forwards the sent copy back to the desktop.
     */
    private suspend fun processDesktopSendRequest(queued: QueuedMessage) {
        val contentBytes = Base64.decode(queued.encryptedContent, Base64.NO_WRAP)
        val json = org.json.JSONObject(String(contentBytes))
        val content = json.optString("content").ifBlank { return }
        val recipientId = json.optString("recipientId").ifBlank { return }
        val senderId = json.optString("senderId").ifBlank { return }
        val conversationId = json.optString("conversationId").ifBlank {
            conversationRepository.createOrGetConversation(recipientId)
        }

        val result = sendMessageUseCase(
            conversationId = conversationId,
            contactId = recipientId,
            senderId = senderId,
            content = content
        )
        if (result.isFailure) {
            Timber.w(result.exceptionOrNull(), "Desktop send request failed for contact %s", recipientId)
        }
    }

    /**
     * Desktop sent a file on behalf of the phone user (content_type=17).
     * Payload JSON: {"fileName":"...","mimeType":"...","fileData":"<base64>","recipientId":"...","senderId":"..."}
     * Phone writes file to a temp location, encrypts it, and sends as a media message.
     */
    private suspend fun processDesktopMediaRequest(queued: QueuedMessage) {
        val contentBytes = Base64.decode(queued.encryptedContent, Base64.NO_WRAP)
        val json = org.json.JSONObject(String(contentBytes))
        val fileName = json.optString("fileName").ifBlank { return }
        val mimeType = json.optString("mimeType").ifBlank { "application/octet-stream" }
        val fileDataB64 = json.optString("fileData").ifBlank { return }
        val recipientId = json.optString("recipientId").ifBlank { return }
        val senderId = json.optString("senderId").ifBlank { return }

        val fileBytes = runCatching {
            java.util.Base64.getDecoder().decode(fileDataB64)
        }.getOrElse { Timber.w(it, "Failed to decode file bytes"); return }

        // Write to a temp file so SendMediaMessageUseCase can process it
        val tempFile = File(context.cacheDir, "desktop_send_${UUID.randomUUID()}_$fileName")
        tempFile.writeBytes(fileBytes)

        try {
            val mediaType = when {
                mimeType.startsWith("image/") -> MediaType.IMAGE
                mimeType.startsWith("video/") -> MediaType.VIDEO
                else -> MediaType.VOICE // VOICE used as generic binary fallback
            }

            val encrypted = mediaEncryptor.encrypt(fileBytes)

            val conversationId = conversationRepository.createOrGetConversation(recipientId)
            val attachment = MediaAttachment(
                mediaId = UUID.randomUUID().toString(),
                mediaType = mediaType,
                fileName = fileName,
                fileSize = fileBytes.size.toLong(),
                mimeType = mimeType,
                localPath = tempFile.absolutePath,
                encryptionKey = encrypted.keyBase64,
                encryptionIv = encrypted.ivBase64
            )

            val result = sendMediaMessageUseCase(
                conversationId = conversationId,
                contactId = recipientId,
                senderId = senderId,
                mediaAttachment = attachment,
                encryptedFileBytes = encrypted.encryptedBytes
            )

            if (result.isFailure) {
                Timber.w(result.exceptionOrNull(), "Desktop media send request failed for contact %s", recipientId)
            }
        } finally {
            tempFile.delete()
        }
    }
}
