package com.meshcipher.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.meshcipher.data.local.entity.ContactEntity
import com.meshcipher.data.local.entity.ConversationEntity
import com.meshcipher.data.local.entity.LinkedDeviceEntity
import com.meshcipher.data.local.entity.MessageEntity

@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        ConversationEntity::class,
        LinkedDeviceEntity::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MeshCipherDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun linkedDeviceDao(): LinkedDeviceDao

    companion object {
        // Migration from version 1 (original) to version 3 (with media + expiry)
        val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN media_id TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN media_type TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN media_metadata_json TEXT")
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

        // Migration from version 5 to version 6 (add linked_devices table)
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS linked_devices (
                        device_id TEXT NOT NULL PRIMARY KEY,
                        device_name TEXT NOT NULL,
                        device_type TEXT NOT NULL,
                        public_key_hex TEXT NOT NULL,
                        linked_at INTEGER NOT NULL,
                        approved INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
