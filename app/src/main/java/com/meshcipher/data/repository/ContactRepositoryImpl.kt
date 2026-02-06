package com.meshcipher.data.repository

import com.meshcipher.data.local.database.ContactDao
import com.meshcipher.data.local.entity.ContactEntity
import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.repository.ContactRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.signal.libsignal.protocol.SignalProtocolAddress
import javax.inject.Inject

class ContactRepositoryImpl @Inject constructor(
    private val contactDao: ContactDao
) : ContactRepository {

    override fun getAllContacts(): Flow<List<Contact>> {
        return contactDao.getAllContacts()
            .map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getContact(contactId: String): Contact? {
        return contactDao.getContact(contactId)?.toDomain()
    }

    override fun getContactFlow(contactId: String): Flow<Contact?> {
        return contactDao.getContactFlow(contactId)
            .map { it?.toDomain() }
    }

    override suspend fun insertContact(contact: Contact) {
        contactDao.insertContact(contact.toEntity())
    }

    override suspend fun updateContact(contact: Contact) {
        contactDao.updateContact(contact.toEntity())
    }

    override suspend fun deleteContact(contact: Contact) {
        contactDao.deleteContact(contact.toEntity())
    }

    private fun ContactEntity.toDomain(): Contact {
        return Contact(
            id = id,
            displayName = displayName,
            publicKey = publicKey,
            identityKey = identityKey,
            signalProtocolAddress = SignalProtocolAddress(
                signalProtocolAddress,
                1
            ),
            lastSeen = lastSeen,
            onionAddress = onionAddress
        )
    }

    private fun Contact.toEntity(): ContactEntity {
        return ContactEntity(
            id = id,
            displayName = displayName,
            publicKey = publicKey,
            identityKey = identityKey,
            signalProtocolAddress = signalProtocolAddress.name,
            lastSeen = lastSeen,
            onionAddress = onionAddress
        )
    }
}
