package com.meshcipher.domain.usecase

import com.meshcipher.data.crypto.SafetyNumberGenerator
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.repository.ContactRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafetyNumberManager @Inject constructor(
    private val safetyNumberGenerator: SafetyNumberGenerator,
    private val contactRepository: ContactRepository,
    private val identityManager: IdentityManager
) {

    /**
     * Compute the current safety number between the local user and a contact.
     * Returns null if local identity is not available.
     */
    suspend fun computeSafetyNumber(contact: Contact): String? {
        val identity = identityManager.getIdentity() ?: return null
        return safetyNumberGenerator.generateSafetyNumber(
            localUserId = identity.userId,
            localPublicKey = identity.hardwarePublicKey,
            remoteUserId = contact.id,
            remotePublicKey = contact.publicKey
        )
    }

    /**
     * Recompute the safety number for a contact and persist it.
     * If it differs from the previously stored value, records the change timestamp.
     */
    suspend fun updateCurrentSafetyNumber(contactId: String) {
        val contact = contactRepository.getContact(contactId) ?: return
        val newSafetyNumber = computeSafetyNumber(contact) ?: return

        if (newSafetyNumber == contact.currentSafetyNumber) return

        val changed = contact.currentSafetyNumber != null &&
            contact.verifiedSafetyNumber != null &&
            newSafetyNumber != contact.verifiedSafetyNumber

        if (changed) {
            Timber.w("Safety number changed for contact %s", contact.displayName)
        }

        contactRepository.updateContact(
            contact.copy(
                currentSafetyNumber = newSafetyNumber,
                safetyNumberChangedAt = if (changed) System.currentTimeMillis()
                                        else contact.safetyNumberChangedAt
            )
        )
    }

    /**
     * Mark the current safety number as user-verified.
     * Clears the change flag and records the verification timestamp.
     */
    suspend fun markAsVerified(contactId: String) {
        val contact = contactRepository.getContact(contactId) ?: return
        val current = contact.currentSafetyNumber ?: computeSafetyNumber(contact) ?: return

        contactRepository.updateContact(
            contact.copy(
                currentSafetyNumber = current,
                verifiedSafetyNumber = current,
                safetyNumberVerifiedAt = System.currentTimeMillis(),
                safetyNumberChangedAt = null
            )
        )
        Timber.i("Safety number verified for contact %s", contact.displayName)
    }

    /**
     * Recompute safety numbers for every contact.
     * Should be called on app startup to detect key rotation after backup restore.
     */
    suspend fun checkAllSafetyNumbers() {
        val identity = identityManager.getIdentity() ?: return
        val contacts = contactRepository.getAllContacts()
        contacts.collect { list ->
            list.forEach { contact ->
                try {
                    val newNumber = safetyNumberGenerator.generateSafetyNumber(
                        localUserId = identity.userId,
                        localPublicKey = identity.hardwarePublicKey,
                        remoteUserId = contact.id,
                        remotePublicKey = contact.publicKey
                    )

                    if (newNumber == contact.currentSafetyNumber) return@forEach

                    val changed = contact.currentSafetyNumber != null &&
                        contact.verifiedSafetyNumber != null &&
                        newNumber != contact.verifiedSafetyNumber

                    if (changed) {
                        Timber.w("Startup: safety number changed for %s", contact.displayName)
                    }

                    contactRepository.updateContact(
                        contact.copy(
                            currentSafetyNumber = newNumber,
                            safetyNumberChangedAt = if (changed) System.currentTimeMillis()
                                                    else contact.safetyNumberChangedAt
                        )
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to update safety number for %s", contact.displayName)
                }
            }
        }
    }

    /**
     * Emit the list of contacts whose safety number has changed since last verification.
     */
    fun observeChangedContacts(): Flow<List<Contact>> {
        return contactRepository.getAllContacts().map { contacts ->
            contacts.filter { it.safetyNumberChanged() }
        }
    }

    fun formatForDisplay(safetyNumber: String): String =
        safetyNumberGenerator.formatForDisplay(safetyNumber)

    fun generateQRContent(contact: Contact): String {
        val number = contact.currentSafetyNumber ?: return ""
        return safetyNumberGenerator.generateQRContent(number)
    }

    fun parseSafetyNumberFromQR(qrContent: String): String? =
        safetyNumberGenerator.parseSafetyNumberFromQR(qrContent)
}
