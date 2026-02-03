package com.meshcipher.domain.usecase

import com.meshcipher.data.transport.TransportManager
import com.meshcipher.domain.model.Message
import com.meshcipher.domain.model.MessageStatus
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.repository.ConversationRepository
import com.meshcipher.domain.repository.MessageRepository
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val contactRepository: ContactRepository,
    private val transportManager: TransportManager
) {

    suspend operator fun invoke(
        conversationId: String,
        contactId: String,
        senderId: String,
        content: String
    ): Result<Message> {
        val contact = contactRepository.getContact(contactId)
            ?: return Result.failure(Exception("Contact not found"))

        val message = Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            senderId = senderId,
            recipientId = contact.id,
            content = content,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            isOwnMessage = true
        )

        // Save message locally first
        messageRepository.insertMessage(message)

        // Send via relay server
        // For now we send plaintext base64-encoded; Signal Protocol encryption
        // will be integrated once key exchange is implemented in a later phase.
        val sendResult = transportManager.getActiveTransport().sendMessage(
            senderId = senderId,
            recipientId = contact.signalProtocolAddress.name,
            encryptedContent = content.toByteArray()
        )

        return if (sendResult.isSuccess) {
            val sentMessage = message.copy(status = MessageStatus.SENT)
            messageRepository.updateMessageStatus(message.id, MessageStatus.SENT)
            Timber.d("Message sent: %s", message.id)
            Result.success(sentMessage)
        } else {
            messageRepository.updateMessageStatus(message.id, MessageStatus.FAILED)
            Timber.e("Message send failed: %s", sendResult.exceptionOrNull()?.message)
            Result.failure(sendResult.exceptionOrNull() ?: Exception("Send failed"))
        }
    }
}
