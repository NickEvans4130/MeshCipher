package com.meshcipher.domain.usecase

import android.content.Context
import android.util.Base64
import com.meshcipher.data.media.MediaEncryptor
import com.meshcipher.data.media.MediaFileManager
import com.meshcipher.data.remote.dto.QueuedMessage
import com.meshcipher.data.service.MessageForwardingService
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
            1 -> processMediaMessage(queued)
            13 -> processUnlinkNotification(queued)
            else -> processTextMessage(queued)
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
}
