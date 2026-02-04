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
    val mediaId: String? = null,
    val mediaType: MediaType? = null,
    val mediaMetadataJson: String? = null
) {
    val isMediaMessage: Boolean get() = mediaId != null

    val mediaMetadata: MediaMetadata?
        get() = mediaMetadataJson?.let { MediaMetadata.fromJson(it) }
}

enum class MessageStatus {
    PENDING,
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}
