package com.meshcipher.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.meshcipher.data.local.entity.ContactEntity
import com.meshcipher.data.local.entity.ConversationEntity
import com.meshcipher.data.local.entity.MessageEntity

@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        ConversationEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MeshCipherDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
}
