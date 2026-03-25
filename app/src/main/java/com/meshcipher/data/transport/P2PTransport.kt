package com.meshcipher.data.transport

import android.content.Context
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.media.MediaEncryptor
import com.meshcipher.data.media.MediaFileManager
import com.meshcipher.data.tor.P2PConnectionManager
import com.meshcipher.data.tor.TorBootstrapVerifier
import com.meshcipher.domain.model.MediaAttachment
import com.meshcipher.domain.model.MediaMessageEnvelope
import com.meshcipher.domain.model.MediaType
import com.meshcipher.domain.model.Message
import com.meshcipher.domain.model.MessageStatus
import com.meshcipher.domain.model.P2PMessage
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.repository.ConversationRepository
import com.meshcipher.domain.repository.MessageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class P2PTransport @Inject constructor(
    private val p2pConnectionManager: P2PConnectionManager,
    private val torBootstrapVerifier: TorBootstrapVerifier,
    private val identityManager: IdentityManager,
    private val contactRepository: ContactRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val mediaEncryptor: MediaEncryptor,
    private val mediaFileManager: MediaFileManager,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize() {
        scope.launch {
            collectIncomingMessages()
        }
        Timber.d("P2PTransport initialized")
    }

    private suspend fun collectIncomingMessages() {
        p2pConnectionManager.incomingMessages.collect { message ->
            handleReceivedMessage(message)
        }
    }

    private suspend fun handleReceivedMessage(message: P2PMessage) {
        val myIdentity = try {
            identityManager.getIdentity()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get identity")
            return
        }

        if (myIdentity == null) {
            Timber.w("No identity available, cannot process P2P message")
            return
        }

        Timber.d("Received P2P message from %s to %s, type=%s",
            message.senderId, message.recipientId, message.type)

        if (message.recipientId != myIdentity.userId) {
            Timber.d("P2P message not for us (dest=%s, me=%s)", message.recipientId, myIdentity.userId)
            return
        }

        when (message.type) {
            P2PMessage.Type.TEXT -> deliverTextMessageLocally(message)
            P2PMessage.Type.MEDIA -> deliverMediaMessageLocally(message)
            P2PMessage.Type.ACK -> Timber.d("Received ACK for message: %s", message.messageId)
            P2PMessage.Type.PING -> Timber.d("Received PING from: %s", message.senderId)
            P2PMessage.Type.PONG -> Timber.d("Received PONG from: %s", message.senderId)
        }
    }

    private suspend fun deliverTextMessageLocally(p2pMessage: P2PMessage) {
        try {
            val payload = p2pMessage.payload ?: return
            val content = String(Base64.getDecoder().decode(payload))

            val contacts = contactRepository.getAllContacts().first()
            val senderContact = contacts.find { contact ->
                contact.id == p2pMessage.senderId
            }

            if (senderContact == null) {
                Timber.w("Received P2P message from unknown sender: %s", p2pMessage.senderId)
                return
            }

            val conversationId = conversationRepository.createOrGetConversation(senderContact.id)

            val message = Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderId = senderContact.id,
                recipientId = "me",
                content = content,
                timestamp = p2pMessage.timestamp,
                status = MessageStatus.DELIVERED,
                isOwnMessage = false
            )

            messageRepository.insertMessage(message)
            conversationRepository.incrementUnreadCount(conversationId)

            Timber.d("Delivered P2P message from %s in conversation %s",
                senderContact.displayName, conversationId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to deliver P2P message locally")
        }
    }

    private suspend fun deliverMediaMessageLocally(p2pMessage: P2PMessage) {
        try {
            val payload = p2pMessage.payload ?: return
            val envelopeJson = String(Base64.getDecoder().decode(payload))
            val envelope = MediaMessageEnvelope.fromJson(envelopeJson)

            val contacts = contactRepository.getAllContacts().first()
            val senderContact = contacts.find { contact ->
                contact.id == p2pMessage.senderId
            }

            if (senderContact == null) {
                Timber.w("Received P2P media from unknown sender: %s", p2pMessage.senderId)
                return
            }

            val encryptedBytes = Base64.getDecoder().decode(envelope.encryptedData)
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
                timestamp = p2pMessage.timestamp,
                status = MessageStatus.DELIVERED,
                isOwnMessage = false,
                mediaAttachment = attachment
            )

            messageRepository.insertMessage(message)
            conversationRepository.incrementUnreadCount(conversationId)

            Timber.d("Delivered P2P media (%s) from %s in conversation %s",
                mediaType, senderContact.displayName, conversationId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to deliver P2P media message locally")
        }
    }

    suspend fun sendMessage(
        recipientUserId: String,
        encryptedContent: ByteArray,
        contentType: Int = 0
    ): Result<String> {
        // R-12: Verify Tor is fully bootstrapped before sending — prevents messages being
        // sent over a partial circuit or before the SOCKS proxy is ready.
        val torCheck = torBootstrapVerifier.verify()
        if (torCheck is TorBootstrapVerifier.VerifyResult.NotReady) {
            Timber.w("P2P send blocked: %s", torCheck.reason)
            return Result.failure(Exception(torCheck.reason))
        }

        if (!isAvailable()) {
            Timber.w("P2P Tor not available, isRunning=%b", p2pConnectionManager.isRunning())
            return Result.failure(Exception("P2P Tor not running"))
        }

        val identity = identityManager.getIdentity()
            ?: return Result.failure(Exception("No identity available"))

        val contacts = contactRepository.getAllContacts().first()
        val recipient = contacts.find { it.id == recipientUserId }
        if (recipient == null) {
            Timber.w("Recipient not found for ID: %s (contacts: %d)", recipientUserId, contacts.size)
            return Result.failure(Exception("Recipient contact not found: $recipientUserId"))
        }

        val onionAddress = recipient.onionAddress
        if (onionAddress == null) {
            Timber.w("Recipient %s has no onion address", recipient.displayName)
            return Result.failure(Exception("Recipient has no .onion address. Re-scan their QR code with P2P Tor running."))
        }

        val messageId = UUID.randomUUID().toString()
        val payload = Base64.getEncoder().encodeToString(encryptedContent)

        val messageType = if (contentType == 1) P2PMessage.Type.MEDIA else P2PMessage.Type.TEXT

        val p2pMessage = P2PMessage(
            type = messageType,
            messageId = messageId,
            senderId = identity.userId,
            recipientId = recipientUserId,
            timestamp = System.currentTimeMillis(),
            payload = payload
        )

        val result = p2pConnectionManager.sendMessage(onionAddress, p2pMessage)
        return if (result.isSuccess) {
            Timber.d("P2P message sent (type=%s)", messageType)
            Result.success(messageId)
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("P2P send failed"))
        }
    }

    fun isAvailable(): Boolean = p2pConnectionManager.isRunning()

    fun isRecipientReachable(recipientUserId: String): Boolean {
        if (!isAvailable()) return false
        return true
    }
}
