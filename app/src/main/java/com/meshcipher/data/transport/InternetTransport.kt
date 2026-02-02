package com.meshcipher.data.transport

import android.util.Base64
import com.meshcipher.data.remote.api.RelayApiService
import com.meshcipher.data.remote.dto.AcknowledgeRequest
import com.meshcipher.data.remote.dto.QueuedMessage
import com.meshcipher.data.remote.dto.RelayMessageRequest
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InternetTransport @Inject constructor(
    private val relayApiService: RelayApiService
) {

    suspend fun sendMessage(
        senderId: String,
        recipientId: String,
        encryptedContent: ByteArray,
        contentType: Int = 0
    ): Result<String> {
        return try {
            val encoded = Base64.encodeToString(encryptedContent, Base64.NO_WRAP)

            val request = RelayMessageRequest(
                senderId = senderId,
                recipientId = recipientId,
                encryptedContent = encoded,
                contentType = contentType
            )

            val response = relayApiService.sendMessage(request)

            if (response.isSuccessful && response.body() != null) {
                val messageId = response.body()!!.messageId
                Timber.d("Message sent successfully: %s", messageId)
                Result.success(messageId)
            } else {
                val error = "Send failed: ${response.code()} ${response.message()}"
                Timber.e(error)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to send message")
            Result.failure(e)
        }
    }

    suspend fun receiveMessages(recipientId: String): Result<List<QueuedMessage>> {
        return try {
            val response = relayApiService.getMessages(recipientId)

            if (response.isSuccessful && response.body() != null) {
                val messages = response.body()!!.messages
                Timber.d("Received %d messages", messages.size)
                Result.success(messages)
            } else {
                val error = "Receive failed: ${response.code()} ${response.message()}"
                Timber.e(error)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to receive messages")
            Result.failure(e)
        }
    }

    suspend fun acknowledgeMessages(
        recipientId: String,
        messageIds: List<String>
    ): Result<Int> {
        return try {
            val request = AcknowledgeRequest(messageIds = messageIds)
            val response = relayApiService.acknowledgeMessages(recipientId, request)

            if (response.isSuccessful && response.body() != null) {
                val count = response.body()!!.acknowledged
                Timber.d("Acknowledged %d messages", count)
                Result.success(count)
            } else {
                val error = "Ack failed: ${response.code()} ${response.message()}"
                Timber.e(error)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to acknowledge messages")
            Result.failure(e)
        }
    }

    suspend fun isServerReachable(): Boolean {
        return try {
            val response = relayApiService.healthCheck()
            response.isSuccessful && response.body()?.status == "healthy"
        } catch (e: Exception) {
            Timber.w(e, "Server unreachable")
            false
        }
    }
}
