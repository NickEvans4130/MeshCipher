package com.meshcipher.shared.domain.model

import com.meshcipher.shared.util.generateUUID
import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String = generateUUID(),
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val content: String,
    val timestamp: Long,
    val status: MessageStatus,
    val isOwnMessage: Boolean = false,
    val mediaAttachment: MediaAttachment? = null
)

@Serializable
enum class MessageStatus {
    PENDING,
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}
