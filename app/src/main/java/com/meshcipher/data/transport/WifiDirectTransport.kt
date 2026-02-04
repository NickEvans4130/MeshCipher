package com.meshcipher.data.transport

import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.wifidirect.WifiDirectManager
import com.meshcipher.data.wifidirect.WifiDirectSocketManager
import com.meshcipher.domain.model.WifiDirectMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiDirectTransport @Inject constructor(
    private val wifiDirectManager: WifiDirectManager,
    private val socketManager: WifiDirectSocketManager,
    private val identityManager: IdentityManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                        Timber.d("Connecting to Group Owner: $goAddress")
                        socketManager.connectToServer(goAddress)
                    }
                }
            }
        }
    }

    fun cleanup() {
        socketManager.stopServer()
        socketManager.disconnectAll()
        wifiDirectManager.cleanup()
    }

    suspend fun sendMessage(
        recipientUserId: String,
        encryptedContent: ByteArray
    ): Result<String> {
        if (!isAvailable()) {
            return Result.failure(Exception("WiFi Direct not available"))
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
