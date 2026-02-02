package com.meshcipher.domain.model

data class Conversation(
    val id: String,
    val contactId: String,
    val contactName: String,
    val lastMessage: String?,
    val lastMessageTime: Long?,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false
)
