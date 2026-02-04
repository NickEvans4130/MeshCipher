package com.meshcipher.data.repository

import com.meshcipher.data.local.database.ConversationDao
import com.meshcipher.data.local.database.ContactDao
import com.meshcipher.data.local.database.MessageDao
import com.meshcipher.data.local.entity.ConversationEntity
import com.meshcipher.domain.model.Conversation
import com.meshcipher.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao
) : ConversationRepository {

    override fun getAllConversations(): Flow<List<Conversation>> {
        return combine(
            conversationDao.getAllConversations(),
            contactDao.getAllContacts()
        ) { conversations, contacts ->
            conversations.mapNotNull { conv ->
                val contact = contacts.find { it.id == conv.contactId }
                contact?.let {
                    Conversation(
                        id = conv.id,
                        contactId = conv.contactId,
                        contactName = contact.displayName,
                        lastMessage = conv.lastMessageId?.let { "Last message" }, // TODO: Fetch actual message
                        lastMessageTime = conv.lastMessageTimestamp,
                        unreadCount = conv.unreadCount,
                        isPinned = conv.isPinned
                    )
                }
            }
        }
    }

    override suspend fun getConversation(conversationId: String): Conversation? {
        val entity = conversationDao.getConversation(conversationId) ?: return null
        val contact = contactDao.getContact(entity.contactId) ?: return null

        return Conversation(
            id = entity.id,
            contactId = entity.contactId,
            contactName = contact.displayName,
            lastMessage = null, // TODO: Fetch
            lastMessageTime = entity.lastMessageTimestamp,
            unreadCount = entity.unreadCount,
            isPinned = entity.isPinned
        )
    }

    override fun getConversationFlow(conversationId: String): Flow<Conversation?> {
        return conversationDao.getConversationFlow(conversationId)
            .combine(contactDao.getAllContacts()) { conv, contacts ->
                conv?.let { entity ->
                    val contact = contacts.find { it.id == entity.contactId }
                    contact?.let {
                        Conversation(
                            id = entity.id,
                            contactId = entity.contactId,
                            contactName = contact.displayName,
                            lastMessage = null,
                            lastMessageTime = entity.lastMessageTimestamp,
                            unreadCount = entity.unreadCount,
                            isPinned = entity.isPinned
                        )
                    }
                }
            }
    }

    override suspend fun createOrGetConversation(contactId: String): String {
        val existing = conversationDao.getConversationByContactId(contactId)
        if (existing != null) {
            return existing.id
        }

        val conversationId = UUID.randomUUID().toString()
        val conversation = ConversationEntity(
            id = conversationId,
            contactId = contactId,
            lastMessageId = null,
            lastMessageTimestamp = null,
            unreadCount = 0,
            isPinned = false
        )

        conversationDao.insertConversation(conversation)
        return conversationId
    }

    override suspend fun markConversationAsRead(conversationId: String) {
        conversationDao.markConversationAsRead(conversationId)
    }

    override suspend fun incrementUnreadCount(conversationId: String) {
        conversationDao.incrementUnreadCount(conversationId)
    }

    override suspend fun togglePin(conversationId: String) {
        conversationDao.togglePin(conversationId)
    }

    override suspend fun deleteConversation(conversationId: String) {
        val conversation = conversationDao.getConversation(conversationId)
        conversation?.let {
            conversationDao.deleteConversation(it)
            messageDao.deleteAllMessagesInConversation(conversationId)
        }
    }

    override fun getUnreadCountsByContact(): Flow<Map<String, Int>> {
        return conversationDao.getAllConversations().map { conversations ->
            conversations.associate { it.contactId to it.unreadCount }
        }
    }
}
