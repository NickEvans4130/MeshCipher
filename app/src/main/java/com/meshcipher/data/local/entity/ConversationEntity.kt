package com.meshcipher.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [Index("last_message_timestamp", orders = [Index.Order.DESC])]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "last_message_id") val lastMessageId: String?,
    @ColumnInfo(name = "last_message_timestamp") val lastMessageTimestamp: Long?,
    @ColumnInfo(name = "unread_count") val unreadCount: Int,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean
)
