package com.meshcipher.data.remote.dto

import com.google.gson.annotations.SerializedName

data class RelayMessageRequest(
    @SerializedName("sender_id")
    val senderId: String,
    @SerializedName("recipient_id")
    val recipientId: String,
    @SerializedName("encrypted_content")
    val encryptedContent: String,
    @SerializedName("content_type")
    val contentType: Int = 0
)

data class RelayMessageResponse(
    @SerializedName("status")
    val status: String,
    @SerializedName("message_id")
    val messageId: String,
    @SerializedName("queued_at")
    val queuedAt: String
)

data class QueuedMessagesResponse(
    @SerializedName("messages")
    val messages: List<QueuedMessage>,
    @SerializedName("count")
    val count: Int
)

data class QueuedMessage(
    @SerializedName("id")
    val id: String,
    @SerializedName("sender_id")
    val senderId: String,
    @SerializedName("recipient_id")
    val recipientId: String,
    @SerializedName("encrypted_content")
    val encryptedContent: String,
    @SerializedName("content_type")
    val contentType: Int,
    @SerializedName("queued_at")
    val queuedAt: String
)

data class AcknowledgeRequest(
    @SerializedName("message_ids")
    val messageIds: List<String>
)

data class AcknowledgeResponse(
    @SerializedName("acknowledged")
    val acknowledged: Int
)

data class RegisterDeviceRequest(
    @SerializedName("device_id")
    val deviceId: String,
    @SerializedName("user_id")
    val userId: String = "",
    @SerializedName("public_key")
    val publicKey: String? = null
)

data class RegisterDeviceResponse(
    @SerializedName("status")
    val status: String,
    @SerializedName("id")
    val id: String
)

data class HealthResponse(
    @SerializedName("status")
    val status: String,
    @SerializedName("database")
    val database: String,
    @SerializedName("queued_messages")
    val queuedMessages: Int,
    @SerializedName("registered_devices")
    val registeredDevices: Int,
    @SerializedName("timestamp")
    val timestamp: String
)
