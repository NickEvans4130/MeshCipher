package com.meshcipher.domain.repository

import com.meshcipher.domain.model.Message
import com.meshcipher.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getMessagesForConversation(conversationId: String): Flow<List<Message>>
    suspend fun insertMessage(message: Message)
    suspend fun updateMessage(message: Message)
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)
    suspend fun deleteMessage(message: Message)
}
