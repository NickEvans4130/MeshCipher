package com.meshcipher.domain.usecase

import com.meshcipher.data.transport.TransportManager
import com.meshcipher.domain.model.MediaAttachment
import com.meshcipher.domain.model.MediaMessageEnvelope
import com.meshcipher.domain.model.Message
import com.meshcipher.domain.model.MessageStatus
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.repository.MessageRepository
import timber.log.Timber
import java.util.Base64
import java.util.UUID
import javax.inject.Inject

class SendMediaMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val contactRepository: ContactRepository,
    private val transportManager: TransportManager
) {

    suspend operator fun invoke(
        conversationId: String,
        contactId: String,
        senderId: String,
        mediaAttachment: MediaAttachment,
        encryptedFileBytes: ByteArray
    ): Result<Message> {
        val contact = contactRepository.getContact(contactId)
            ?: return Result.failure(Exception("Contact not found"))

        val message = Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            senderId = senderId,
            recipientId = contact.id,
            content = "[${mediaAttachment.mediaType.name}]",
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            isOwnMessage = true,
            mediaAttachment = mediaAttachment
        )

        messageRepository.insertMessage(message)

        val envelope = MediaMessageEnvelope(
            mediaId = mediaAttachment.mediaId,
            mediaType = mediaAttachment.mediaType.name,
            fileName = mediaAttachment.fileName,
            mimeType = mediaAttachment.mimeType,
            fileSize = mediaAttachment.fileSize,
            width = mediaAttachment.width,
            height = mediaAttachment.height,
            durationMs = mediaAttachment.durationMs,
            encryptionKey = mediaAttachment.encryptionKey,
            encryptionIv = mediaAttachment.encryptionIv,
            encryptedData = Base64.getEncoder().encodeToString(encryptedFileBytes)
        )

        val envelopeBytes = envelope.toJson().toByteArray()

        val sendResult = transportManager.sendWithFallback(
            senderId = senderId,
            recipientId = contact.id,
            encryptedContent = envelopeBytes,
            contentType = 1
        )

        return if (sendResult.isSuccess) {
            val sentMessage = message.copy(status = MessageStatus.SENT)
            messageRepository.updateMessageStatus(message.id, MessageStatus.SENT)
            Timber.d("Media message sent: %s (type=%s)", message.id, mediaAttachment.mediaType)
            Result.success(sentMessage)
        } else {
            messageRepository.updateMessageStatus(message.id, MessageStatus.FAILED)
            Timber.e("Media message send failed: %s", sendResult.exceptionOrNull()?.message)
            Result.failure(sendResult.exceptionOrNull() ?: Exception("Send failed"))
        }
    }
}
