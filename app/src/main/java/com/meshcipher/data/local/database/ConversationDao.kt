package com.meshcipher.data.local.database

import androidx.room.*
import com.meshcipher.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY is_pinned DESC, last_message_timestamp DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversation(conversationId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    fun getConversationFlow(conversationId: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE contact_id = :contactId LIMIT 1")
    suspend fun getConversationByContactId(contactId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unread_count = 0 WHERE id = :conversationId")
    suspend fun markConversationAsRead(conversationId: String)

    @Query("UPDATE conversations SET unread_count = unread_count + 1 WHERE id = :conversationId")
    suspend fun incrementUnreadCount(conversationId: String)

    @Query("UPDATE conversations SET is_pinned = NOT is_pinned WHERE id = :conversationId")
    suspend fun togglePin(conversationId: String)

    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)
}
