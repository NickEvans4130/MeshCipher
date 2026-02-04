package com.meshcipher.di

import android.content.Context
import androidx.room.Room
import com.meshcipher.data.local.database.ContactDao
import com.meshcipher.data.local.database.ConversationDao
import com.meshcipher.data.local.database.MeshCipherDatabase
import com.meshcipher.data.local.database.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): MeshCipherDatabase {
        // TODO: Use proper key derivation in production
        val passphrase = SQLiteDatabase.getBytes("meshcipher_secret_key".toCharArray())
        val factory = SupportFactory(passphrase)

        return Room.databaseBuilder(
            context,
            MeshCipherDatabase::class.java,
            "meshcipher.db"
        )
            .openHelperFactory(factory)
            .addMigrations(MeshCipherDatabase.MIGRATION_1_2)
            .build()
    }

    @Provides
    fun provideMessageDao(database: MeshCipherDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    fun provideContactDao(database: MeshCipherDatabase): ContactDao {
        return database.contactDao()
    }

    @Provides
    fun provideConversationDao(database: MeshCipherDatabase): ConversationDao {
        return database.conversationDao()
    }
}
