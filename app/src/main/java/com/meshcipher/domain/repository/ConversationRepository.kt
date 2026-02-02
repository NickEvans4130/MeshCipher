package com.meshcipher.domain.repository

import com.meshcipher.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun getAllConversations(): Flow<List<Conversation>>
    suspend fun getConversation(conversationId: String): Conversation?
    fun getConversationFlow(conversationId: String): Flow<Conversation?>
    suspend fun createOrGetConversation(contactId: String): String
    suspend fun markConversationAsRead(conversationId: String)
    suspend fun incrementUnreadCount(conversationId: String)
    suspend fun togglePin(conversationId: String)
    suspend fun deleteConversation(conversationId: String)
}
