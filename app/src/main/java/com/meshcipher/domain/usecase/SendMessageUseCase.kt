package com.meshcipher.domain.usecase

import android.util.Base64
import com.meshcipher.data.ipfs.IpfsManager
import com.meshcipher.data.transport.TransportManager
import com.meshcipher.domain.model.MediaMetadata
import com.meshcipher.domain.model.MediaType
import com.meshcipher.domain.model.Message
import com.meshcipher.domain.model.MessageStatus
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.repository.ConversationRepository
import com.meshcipher.domain.repository.MessageRepository
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val contactRepository: ContactRepository,
    private val transportManager: TransportManager,
    private val ipfsManager: IpfsManager
) {

    suspend operator fun invoke(
        conversationId: String,
        contactId: String,
        senderId: String,
        content: String,
        mediaId: String? = null,
        mediaType: MediaType? = null,
        mediaMetadataJson: String? = null
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
            isOwnMessage = true,
            mediaId = mediaId,
            mediaType = mediaType,
            mediaMetadataJson = mediaMetadataJson
        )

        // Save message locally first
        messageRepository.insertMessage(message)

        // Build transport payload - include media metadata if present
        val payload = if (mediaMetadataJson != null) {
            val envelope = JSONObject().apply {
                put("content", content)
                put("mediaId", mediaId)
                put("mediaType", mediaType?.name)
                put("mediaMetadata", mediaMetadataJson)
            }

            // For P2P: include raw media data so receiver can display it
            try {
                val metadata = MediaMetadata.fromJson(mediaMetadataJson)
                if (metadata != null) {
                    // Download all chunks and combine into raw media data
                    val mediaBytes = java.io.ByteArrayOutputStream()
                    for (cid in metadata.chunkCids) {
                        val chunkResult = ipfsManager.download(cid)
                        if (chunkResult.isSuccess) {
                            mediaBytes.write(chunkResult.getOrThrow())
                        }
                    }
                    if (mediaBytes.size() > 0) {
                        envelope.put("mediaData", Base64.encodeToString(
                            mediaBytes.toByteArray(), Base64.NO_WRAP))
                    }

                    // Include thumbnail if available
                    if (metadata.thumbnailCid != null) {
                        val thumbResult = ipfsManager.download(metadata.thumbnailCid)
                        if (thumbResult.isSuccess) {
                            envelope.put("thumbnailData", Base64.encodeToString(
                                thumbResult.getOrThrow(), Base64.NO_WRAP))
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to include media data in envelope")
            }

            envelope.toString().toByteArray()
        } else {
            content.toByteArray()
        }

        val sendResult = transportManager.sendWithFallback(
            senderId = senderId,
            recipientId = contact.signalProtocolAddress.name,
            encryptedContent = payload
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
