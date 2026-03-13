package com.meshcipher.desktop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

data class DesktopContact(
    val id: Long,
    val contactId: String,
    val displayName: String,
    val publicKeyHex: String,
    val linkedAt: Long,
    val lastSeen: Long?
)

data class DesktopMessage(
    val id: Long,
    val messageId: String,
    val contactId: String,
    val content: String,
    val isOutgoing: Boolean,
    val timestamp: Long,
    val status: String
)

object ContactRepository {

    suspend fun getContact(contactId: String): DesktopContact? = withContext(Dispatchers.IO) {
        transaction {
            ContactsTable.select { ContactsTable.contactId eq contactId }
                .firstOrNull()?.toDesktopContact()
        }
    }

    suspend fun getAllContacts(): List<DesktopContact> = withContext(Dispatchers.IO) {
        transaction {
            ContactsTable.selectAll()
                .orderBy(ContactsTable.displayName)
                .map { it.toDesktopContact() }
        }
    }

    suspend fun upsertContact(
        contactId: String,
        displayName: String,
        publicKeyHex: String
    ): DesktopContact = withContext(Dispatchers.IO) {
        transaction {
            val existing = ContactsTable.select { ContactsTable.contactId eq contactId }.firstOrNull()
            if (existing == null) {
                val id = ContactsTable.insert {
                    it[ContactsTable.contactId] = contactId
                    it[ContactsTable.displayName] = displayName
                    it[ContactsTable.publicKeyHex] = publicKeyHex
                    it[ContactsTable.linkedAt] = System.currentTimeMillis()
                } get ContactsTable.id
                ContactsTable.select { ContactsTable.id eq id }
                    .first().toDesktopContact()
            } else {
                ContactsTable.update({ ContactsTable.contactId eq contactId }) {
                    it[ContactsTable.displayName] = displayName
                    it[ContactsTable.publicKeyHex] = publicKeyHex
                }
                existing.toDesktopContact()
            }
        }
    }

    suspend fun getMessagesForContact(contactId: String): List<DesktopMessage> =
        withContext(Dispatchers.IO) {
            transaction {
                MessagesTable.select { MessagesTable.contactId eq contactId }
                    .orderBy(MessagesTable.timestamp)
                    .map { it.toDesktopMessage() }
            }
        }

    suspend fun insertMessage(
        messageId: String,
        contactId: String,
        content: String,
        isOutgoing: Boolean,
        status: String = "sent"
    ): DesktopMessage = withContext(Dispatchers.IO) {
        transaction {
            val id = MessagesTable.insert {
                it[MessagesTable.messageId] = messageId
                it[MessagesTable.contactId] = contactId
                it[MessagesTable.content] = content
                it[MessagesTable.isOutgoing] = isOutgoing
                it[MessagesTable.timestamp] = System.currentTimeMillis()
                it[MessagesTable.status] = status
            } get MessagesTable.id
            MessagesTable.select { MessagesTable.id eq id }
                .first().toDesktopMessage()
        }
    }

    suspend fun updateMessageStatus(messageId: String, status: String) =
        withContext(Dispatchers.IO) {
            transaction {
                MessagesTable.update({ MessagesTable.messageId eq messageId }) {
                    it[MessagesTable.status] = status
                }
            }
        }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        transaction { ContactsTable.deleteAll() }
    }

    private fun ResultRow.toDesktopContact() = DesktopContact(
        id = this[ContactsTable.id].value,
        contactId = this[ContactsTable.contactId],
        displayName = this[ContactsTable.displayName],
        publicKeyHex = this[ContactsTable.publicKeyHex],
        linkedAt = this[ContactsTable.linkedAt],
        lastSeen = this[ContactsTable.lastSeen]
    )

    private fun ResultRow.toDesktopMessage() = DesktopMessage(
        id = this[MessagesTable.id].value,
        messageId = this[MessagesTable.messageId],
        contactId = this[MessagesTable.contactId],
        content = this[MessagesTable.content],
        isOutgoing = this[MessagesTable.isOutgoing],
        timestamp = this[MessagesTable.timestamp],
        status = this[MessagesTable.status]
    )
}
