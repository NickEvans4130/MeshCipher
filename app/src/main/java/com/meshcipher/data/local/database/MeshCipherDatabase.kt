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
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MeshCipherDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        // Migration from version 1 (original) to version 3 (with media + expiry)
        val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add media columns to messages (from phase-4.5)
                db.execSQL("ALTER TABLE messages ADD COLUMN media_id TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN media_type TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN media_metadata_json TEXT")
                // Add expiry column to conversations (phase-5)
                db.execSQL("ALTER TABLE conversations ADD COLUMN message_expiry_mode TEXT")
            }
        }

        // Migration from version 2 (phase-4.5 with media columns) to version 3 (with expiry)
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN message_expiry_mode TEXT")
            }
        }

        // Migration from version 3 to version 4 (add onion_address to contacts)
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN onion_address TEXT")
            }
        }

        // Migration from version 4 to version 5 (add safety number tracking to contacts)
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN current_safety_number TEXT")
                db.execSQL("ALTER TABLE contacts ADD COLUMN verified_safety_number TEXT")
                db.execSQL("ALTER TABLE contacts ADD COLUMN safety_number_verified_at INTEGER")
                db.execSQL("ALTER TABLE contacts ADD COLUMN safety_number_changed_at INTEGER")
            }
        }
    }
}
