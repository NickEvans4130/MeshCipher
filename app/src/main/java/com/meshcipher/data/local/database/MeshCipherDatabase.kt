package com.meshcipher.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.meshcipher.data.local.entity.ContactEntity
import com.meshcipher.data.local.entity.ConversationEntity
import com.meshcipher.data.local.entity.MessageEntity

@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        ConversationEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MeshCipherDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN media_id TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN media_type TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN media_metadata_json TEXT")
            }
        }
    }
}
