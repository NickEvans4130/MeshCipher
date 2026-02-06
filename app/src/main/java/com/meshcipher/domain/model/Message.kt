package com.meshcipher.domain.model

import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus,
    val isOwnMessage: Boolean = false,
    val mediaAttachment: MediaAttachment? = null
)

enum class MessageStatus {
    PENDING,
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}
