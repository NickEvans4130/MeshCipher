package com.meshcipher.domain.repository

import com.meshcipher.domain.model.Contact
import kotlinx.coroutines.flow.Flow

interface ContactRepository {
    fun getAllContacts(): Flow<List<Contact>>
    suspend fun getContact(contactId: String): Contact?
    fun getContactFlow(contactId: String): Flow<Contact?>
    suspend fun insertContact(contact: Contact)
    suspend fun updateContact(contact: Contact)
    suspend fun deleteContact(contact: Contact)
}
