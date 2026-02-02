package com.meshcipher.data.local.database

import androidx.room.*
import com.meshcipher.data.local.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY display_name ASC")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE id = :contactId")
    suspend fun getContact(contactId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE id = :contactId")
    fun getContactFlow(contactId: String): Flow<ContactEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)

    @Update
    suspend fun updateContact(contact: ContactEntity)

    @Delete
    suspend fun deleteContact(contact: ContactEntity)

    @Query("SELECT COUNT(*) FROM contacts")
    suspend fun getContactCount(): Int
}
