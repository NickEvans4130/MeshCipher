package com.meshcipher.domain.usecase

import android.util.Base64
import com.meshcipher.data.remote.dto.QueuedMessage
import com.meshcipher.data.transport.InternetTransport
import com.meshcipher.domain.model.Message
import com.meshcipher.domain.model.MessageStatus
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.repository.ConversationRepository
import com.meshcipher.domain.repository.MessageRepository
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

class ReceiveMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val contactRepository: ContactRepository,
    private val internetTransport: InternetTransport
) {

    suspend operator fun invoke(localDeviceId: String): Result<Int> {
        val receiveResult = internetTransport.receiveMessages(localDeviceId)

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
            internetTransport.acknowledgeMessages(localDeviceId, ackIds)
        }

        Timber.d("Processed %d/%d messages", processedCount, queuedMessages.size)
        return Result.success(processedCount)
    }

    private suspend fun processQueuedMessage(queued: QueuedMessage) {
        // Decode content - currently plaintext base64, will be encrypted in later phase
        val contentBytes = Base64.decode(queued.encryptedContent, Base64.NO_WRAP)
        val content = String(contentBytes)

        // Find the contact by their signal protocol address name
        val contacts = contactRepository.getAllContacts().first()
        val senderContact = contacts.find {
            it.signalProtocolAddress.name == queued.senderId
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

        Timber.d("Received message from %s in conversation %s",
            senderContact.displayName, conversationId)
    }
}
