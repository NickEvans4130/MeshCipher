package com.meshcipher.desktop.data

import com.meshcipher.desktop.platform.DesktopPlatform
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object ContactsTable : LongIdTable("contacts") {
    val contactId = varchar("contact_id", 128).uniqueIndex()
    val displayName = varchar("display_name", 256)
    val publicKeyHex = varchar("public_key_hex", 512)
    val linkedAt = long("linked_at")
    val lastSeen = long("last_seen").nullable()
}

object MessagesTable : LongIdTable("messages") {
    val messageId = varchar("message_id", 128).uniqueIndex()
    val contactId = varchar("contact_id", 128)
    val content = text("content")
    val isOutgoing = bool("is_outgoing")
    val timestamp = long("timestamp")
    val status = varchar("status", 32)
}

object DeviceLinksTable : LongIdTable("device_links") {
    val deviceId = varchar("device_id", 128).uniqueIndex()
    val deviceName = varchar("device_name", 256)
    val publicKeyHex = varchar("public_key_hex", 512)
    val linkedAt = long("linked_at")
    val approved = bool("approved").default(false)
    val phoneUserId = varchar("phone_user_id", 128).default("")
}

/** Persists ECDH / Signal Protocol session state per contact. */
object SessionsTable : LongIdTable("sessions") {
    val userId = varchar("user_id", 128).uniqueIndex()
    val sessionKeyHex = varchar("session_key_hex", 64) // 32-byte AES key as hex
    val updatedAt = long("updated_at")
}

object AppDatabase {

    fun init() {
        Database.connect(
            url = "jdbc:sqlite:${DesktopPlatform.dbFile.absolutePath}",
            driver = "org.sqlite.JDBC"
        )
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                ContactsTable,
                MessagesTable,
                DeviceLinksTable,
                SessionsTable
            )
        }
    }
}
