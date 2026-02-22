package com.meshcipher.data.transport

import android.content.Context
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.media.MediaEncryptor
import com.meshcipher.data.media.MediaFileManager
import com.meshcipher.data.wifidirect.WifiDirectManager
import com.meshcipher.data.wifidirect.WifiDirectSocketManager
import com.meshcipher.domain.model.MediaAttachment
import com.meshcipher.domain.model.MediaMessageEnvelope
import com.meshcipher.domain.model.MediaType
import com.meshcipher.domain.model.Message
import com.meshcipher.domain.model.MessageStatus
import com.meshcipher.domain.model.WifiDirectMessage
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.repository.ConversationRepository
import com.meshcipher.domain.repository.MessageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private data class PendingFile(
    val metadata: WifiDirectMessage.FileTransfer,
    val chunks: Array<ByteArray?>,
    var receivedCount: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PendingFile
        return metadata == other.metadata
    }

    override fun hashCode(): Int = metadata.hashCode()
}

@Singleton
class WifiDirectTransport @Inject constructor(
    private val wifiDirectManager: WifiDirectManager,
    private val socketManager: WifiDirectSocketManager,
    private val identityManager: IdentityManager,
    private val contactRepository: ContactRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val mediaEncryptor: MediaEncryptor,
    private val mediaFileManager: MediaFileManager,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingFileTransfers = ConcurrentHashMap<String, PendingFile>()

    val incomingMessages: SharedFlow<WifiDirectMessage> = socketManager.receivedMessages

    fun initialize() {
        wifiDirectManager.initialize()

        // Start server if we become group owner
        scope.launch {
            wifiDirectManager.connectionInfo.collect { info ->
                if (info?.groupFormed == true && info.isGroupOwner) {
                    Timber.d("We are Group Owner, starting server")
                    socketManager.startServer()
                } else if (info?.groupFormed == true && !info.isGroupOwner) {
                    // Connect to group owner as client
                    val goAddress = info.groupOwnerAddress?.hostAddress
                    if (goAddress != null) {
                        Timber.d("Connecting to Group Owner")
                        socketManager.connectToServer(goAddress)
                    }
                }
            }
        }

        // Collect and handle incoming messages
        scope.launch {
            collectIncomingMessages()
        }
    }

    private suspend fun collectIncomingMessages() {
        socketManager.receivedMessages.collect { message ->
            handleReceivedMessage(message)
        }
    }

    private suspend fun handleReceivedMessage(message: WifiDirectMessage) {
        val myIdentity = try {
            identityManager.getIdentity()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get identity")
            return
        }

        if (myIdentity == null) {
            Timber.w("No identity available, cannot process WiFi Direct message")
            return
        }

        Timber.d("Received WiFi Direct message from %s to %s", message.senderId, message.recipientId)

        // Check if message is for us
        if (message.recipientId == myIdentity.userId) {
            when (message) {
                is WifiDirectMessage.TextMessage -> {
                    deliverTextMessageLocally(message)
                }
                is WifiDirectMessage.FileTransfer -> {
                    handleFileTransferMetadata(message)
                }
                is WifiDirectMessage.FileChunk -> {
                    handleFileChunk(message)
                }
                is WifiDirectMessage.Acknowledgment -> {
                    Timber.d("Received acknowledgment for message: ${message.messageId}")
                }
            }
        } else {
            Timber.d("Message not for us (dest=%s, me=%s)", message.recipientId, myIdentity.userId)
        }
    }

    private fun handleFileTransferMetadata(transfer: WifiDirectMessage.FileTransfer) {
        Timber.d("Received file transfer metadata: %s (%d chunks)", transfer.fileName, transfer.totalChunks)
        pendingFileTransfers[transfer.fileId] = PendingFile(
            metadata = transfer,
            chunks = arrayOfNulls(transfer.totalChunks)
        )
    }

    private suspend fun handleFileChunk(chunk: WifiDirectMessage.FileChunk) {
        val pending = pendingFileTransfers[chunk.fileId]
        if (pending == null) {
            Timber.w("Received chunk for unknown file: %s", chunk.fileId)
            return
        }

        if (chunk.chunkIndex < 0 || chunk.chunkIndex >= pending.chunks.size) {
            Timber.w("Invalid chunk index %d for file %s", chunk.chunkIndex, chunk.fileId)
            return
        }

        pending.chunks[chunk.chunkIndex] = chunk.data
        pending.receivedCount++

        Timber.d("Received chunk %d/%d for file %s",
            pending.receivedCount, pending.metadata.totalChunks, chunk.fileId)

        if (pending.receivedCount >= pending.metadata.totalChunks) {
            pendingFileTransfers.remove(chunk.fileId)
            assembleAndDeliverFile(pending)
        }
    }

    private suspend fun assembleAndDeliverFile(pending: PendingFile) {
        try {
            val outputStream = ByteArrayOutputStream()
            for (chunk in pending.chunks) {
                if (chunk == null) {
                    Timber.e("Missing chunk in file %s", pending.metadata.fileId)
                    return
                }
                outputStream.write(chunk)
            }
            val assembledBytes = outputStream.toByteArray()

            // The assembled data is a MediaMessageEnvelope JSON
            val envelopeJson = String(assembledBytes)
            val envelope = MediaMessageEnvelope.fromJson(envelopeJson)

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

            val contacts = contactRepository.getAllContacts().first()
            val senderContact = contacts.find { contact ->
                contact.id == pending.metadata.senderId
            }

            if (senderContact == null) {
                Timber.w("File from unknown sender: %s", pending.metadata.senderId)
                return
            }

            val conversationId = conversationRepository.createOrGetConversation(senderContact.id)

            val message = Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderId = senderContact.id,
                recipientId = "me",
                content = "[${mediaType.name}]",
                timestamp = pending.metadata.timestamp,
                status = MessageStatus.DELIVERED,
                isOwnMessage = false,
                mediaAttachment = attachment
            )

            messageRepository.insertMessage(message)
            conversationRepository.incrementUnreadCount(conversationId)

            Timber.d("Delivered WiFi Direct media (%s) from %s", mediaType, senderContact.displayName)
        } catch (e: Exception) {
            Timber.e(e, "Failed to assemble/deliver WiFi Direct file")
        }
    }

    private suspend fun deliverTextMessageLocally(wifiDirectMessage: WifiDirectMessage.TextMessage) {
        try {
            // Decode the message content
            val content = String(wifiDirectMessage.encryptedContent)

            // Find the sender contact by their user ID
            val contacts = contactRepository.getAllContacts().first()
            val senderContact = contacts.find { contact ->
                contact.id == wifiDirectMessage.senderId
            }

            if (senderContact == null) {
                Timber.w("Received WiFi Direct message from unknown sender: %s", wifiDirectMessage.senderId)
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
                timestamp = wifiDirectMessage.timestamp,
                status = MessageStatus.DELIVERED,
                isOwnMessage = false
            )

            messageRepository.insertMessage(message)
            conversationRepository.incrementUnreadCount(conversationId)

            Timber.d("Delivered WiFi Direct message from %s in conversation %s",
                senderContact.displayName, conversationId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to deliver WiFi Direct message locally")
        }
    }

    fun cleanup() {
        pendingFileTransfers.clear()
        socketManager.stopServer()
        socketManager.disconnectAll()
        wifiDirectManager.cleanup()
    }

    suspend fun sendMessage(
        recipientUserId: String,
        encryptedContent: ByteArray,
        contentType: Int = 0
    ): Result<String> {
        if (!isAvailable()) {
            return Result.failure(Exception("WiFi Direct not available"))
        }

        // Media messages use the chunked file transfer path
        if (contentType == 1) {
            val fileId = UUID.randomUUID().toString()
            return sendFile(
                recipientUserId = recipientUserId,
                fileId = fileId,
                fileName = "media_envelope.json",
                fileSize = encryptedContent.size.toLong(),
                mimeType = "application/json",
                fileData = encryptedContent
            )
        }

        val identity = identityManager.getIdentity()
            ?: return Result.failure(Exception("No identity"))

        val messageId = UUID.randomUUID().toString()
        val message = WifiDirectMessage.TextMessage(
            senderId = identity.userId,
            recipientId = recipientUserId,
            timestamp = System.currentTimeMillis(),
            encryptedContent = encryptedContent
        )

        val goAddress = wifiDirectManager.getGroupOwnerAddress()
        val isGO = wifiDirectManager.isGroupOwner()

        return if (isGO) {
            // We are GO, broadcast to all clients
            val sent = socketManager.broadcastMessage(message)
            if (sent > 0) {
                Timber.d("WiFi Direct message $messageId broadcast to $sent clients")
                Result.success(messageId)
            } else {
                Result.failure(Exception("No clients connected"))
            }
        } else if (goAddress != null) {
            // We are client, send to GO
            val success = socketManager.sendMessage(goAddress, message)
            if (success) {
                Timber.d("WiFi Direct message $messageId sent to GO")
                Result.success(messageId)
            } else {
                Result.failure(Exception("Failed to send to Group Owner"))
            }
        } else {
            Result.failure(Exception("Not connected to any group"))
        }
    }

    suspend fun sendFile(
        recipientUserId: String,
        fileId: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        fileData: ByteArray
    ): Result<String> {
        if (!isAvailable()) {
            return Result.failure(Exception("WiFi Direct not available"))
        }

        val identity = identityManager.getIdentity()
            ?: return Result.failure(Exception("No identity"))

        val chunkSize = WifiDirectMessage.CHUNK_SIZE
        val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()

        // Send file metadata
        val metadataMessage = WifiDirectMessage.FileTransfer(
            senderId = identity.userId,
            recipientId = recipientUserId,
            timestamp = System.currentTimeMillis(),
            fileId = fileId,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            totalChunks = totalChunks
        )

        val targetAddress = getTargetAddress() ?: return Result.failure(Exception("No target address"))

        if (!socketManager.sendMessage(targetAddress, metadataMessage)) {
            return Result.failure(Exception("Failed to send file metadata"))
        }

        // Send chunks
        var offset = 0
        var chunkIndex = 0
        while (offset < fileData.size) {
            val endOffset = minOf(offset + chunkSize, fileData.size)
            val chunkData = fileData.copyOfRange(offset, endOffset)

            val chunkMessage = WifiDirectMessage.FileChunk(
                senderId = identity.userId,
                recipientId = recipientUserId,
                timestamp = System.currentTimeMillis(),
                fileId = fileId,
                chunkIndex = chunkIndex,
                data = chunkData
            )

            if (!socketManager.sendMessage(targetAddress, chunkMessage)) {
                return Result.failure(Exception("Failed to send chunk $chunkIndex"))
            }

            offset = endOffset
            chunkIndex++

            Timber.d("Sent chunk $chunkIndex/$totalChunks for file $fileId")
        }

        Timber.d("File transfer complete: $fileName ($totalChunks chunks)")
        return Result.success(fileId)
    }

    private fun getTargetAddress(): String? {
        return if (wifiDirectManager.isGroupOwner()) {
            // Broadcast to first connected client (simplified)
            socketManager.connectedClients.value.firstOrNull()
        } else {
            wifiDirectManager.getGroupOwnerAddress()
        }
    }

    fun isAvailable(): Boolean {
        return wifiDirectManager.isConnected() && socketManager.hasAnyConnection()
    }

    fun isRecipientReachable(recipientUserId: String): Boolean {
        // For WiFi Direct, if we're connected we can reach anyone in the group
        return isAvailable()
    }

    fun startDiscovery() {
        wifiDirectManager.startDiscovery()
    }

    fun stopDiscovery() {
        wifiDirectManager.stopDiscovery()
    }
}
