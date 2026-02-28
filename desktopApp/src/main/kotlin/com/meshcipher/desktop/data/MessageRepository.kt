package com.meshcipher.desktop.data

import com.meshcipher.shared.util.generateUUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

object MessageRepository {

    suspend fun save(
        contactId: String,
        content: String,
        isOutgoing: Boolean,
        status: String = "sent",
        messageId: String = generateUUID(),
        timestamp: Long = System.currentTimeMillis()
    ): DesktopMessage = withContext(Dispatchers.IO) {
        transaction {
            val rowId = MessagesTable.insert {
                it[MessagesTable.messageId] = messageId
                it[MessagesTable.contactId] = contactId
                it[MessagesTable.content] = content
                it[MessagesTable.isOutgoing] = isOutgoing
                it[MessagesTable.timestamp] = timestamp
                it[MessagesTable.status] = status
            } get MessagesTable.id
            MessagesTable.select { MessagesTable.id eq rowId }
                .first().toDesktopMessage()
        }
    }

    suspend fun getForContact(contactId: String): List<DesktopMessage> =
        withContext(Dispatchers.IO) {
            transaction {
                MessagesTable.select { MessagesTable.contactId eq contactId }
                    .orderBy(MessagesTable.timestamp to SortOrder.ASC)
                    .map { it.toDesktopMessage() }
            }
        }

    suspend fun updateStatus(messageId: String, status: String) =
        withContext(Dispatchers.IO) {
            transaction {
                MessagesTable.update({ MessagesTable.messageId eq messageId }) {
                    it[MessagesTable.status] = status
                }
            }
        }

    // --- Session key persistence ---

    suspend fun loadSessionKey(userId: String): String? = withContext(Dispatchers.IO) {
        transaction {
            SessionsTable.select { SessionsTable.userId eq userId }
                .firstOrNull()?.get(SessionsTable.sessionKeyHex)
        }
    }

    suspend fun saveSessionKey(userId: String, keyHex: String) =
        withContext(Dispatchers.IO) {
            transaction {
                val existing = SessionsTable.select { SessionsTable.userId eq userId }.firstOrNull()
                if (existing == null) {
                    SessionsTable.insert {
                        it[SessionsTable.userId] = userId
                        it[SessionsTable.sessionKeyHex] = keyHex
                        it[SessionsTable.updatedAt] = System.currentTimeMillis()
                    }
                } else {
                    SessionsTable.update({ SessionsTable.userId eq userId }) {
                        it[SessionsTable.sessionKeyHex] = keyHex
                        it[SessionsTable.updatedAt] = System.currentTimeMillis()
                    }
                }
            }
        }

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
