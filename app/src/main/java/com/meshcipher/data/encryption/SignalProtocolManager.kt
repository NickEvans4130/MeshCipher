package com.meshcipher.data.encryption

import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignalProtocolManager @Inject constructor(
    private val signalProtocolStore: SignalProtocolStoreImpl,
    private val preKeyManager: PreKeyManager
) {

    fun encryptMessage(
        plaintext: String,
        recipientAddress: SignalProtocolAddress
    ): CiphertextMessage {
        val sessionCipher = SessionCipher(signalProtocolStore, recipientAddress)
        return sessionCipher.encrypt(plaintext.toByteArray())
    }

    fun decryptMessage(
        ciphertext: CiphertextMessage,
        senderAddress: SignalProtocolAddress
    ): String {
        val sessionCipher = SessionCipher(signalProtocolStore, senderAddress)

        val plaintext = when (ciphertext.type) {
            CiphertextMessage.PREKEY_TYPE -> {
                sessionCipher.decrypt(PreKeySignalMessage(ciphertext.serialize()))
            }
            CiphertextMessage.WHISPER_TYPE -> {
                sessionCipher.decrypt(SignalMessage(ciphertext.serialize()))
            }
            else -> throw IllegalArgumentException("Unknown ciphertext type: ${ciphertext.type}")
        }

        return String(plaintext)
    }

    fun createSession(
        contact: com.meshcipher.domain.model.Contact,
        preKeyBundle: PreKeyBundle
    ) {
        val hasPqxdh = try { preKeyBundle.kyberPreKeyId > 0 } catch (e: Exception) { false }
        Timber.d("Creating session with %s (PQXDH=%b)", contact.displayName, hasPqxdh)
        val sessionBuilder = SessionBuilder(
            signalProtocolStore,
            SignalProtocolAddress(contact.id, 1)
        )
        sessionBuilder.process(preKeyBundle)
    }

    fun getSafetyNumber(contact: com.meshcipher.domain.model.Contact): String {
        val identityKey = signalProtocolStore.getIdentity(SignalProtocolAddress(contact.id, 1))
        val myIdentityKey = signalProtocolStore.identityKeyPair

        if (identityKey == null) {
            return "No identity key found"
        }

        return generateSafetyNumber(myIdentityKey.publicKey, identityKey)
    }

    private fun generateSafetyNumber(
        localKey: IdentityKey,
        remoteKey: IdentityKey
    ): String {
        val combined = localKey.serialize() + remoteKey.serialize()
        val hash = combined.contentHashCode()
        return hash.toString().take(12).padEnd(12, '0')
    }
}
