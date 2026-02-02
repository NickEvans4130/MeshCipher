package com.meshcipher.data.encryption

import android.content.Context
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.groups.state.InMemorySenderKeyStore
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.*
import org.signal.libsignal.protocol.state.impl.*
import org.signal.libsignal.protocol.util.KeyHelper as SignalKeyHelper
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignalProtocolStoreImpl @Inject constructor(
    private val context: Context
) : SignalProtocolStore {

    private val inMemoryStore: InMemorySignalProtocolStore
    private val senderKeyStore = InMemorySenderKeyStore()
    private val kyberPreKeyStore = InMemoryKyberPreKeyStore()

    init {
        val identityKeyPair = IdentityKeyPair.generate()
        val registrationId = SignalKeyHelper.generateRegistrationId(false)
        inMemoryStore = InMemorySignalProtocolStore(identityKeyPair, registrationId)
    }

    // IdentityKeyStore
    override fun getIdentityKeyPair(): IdentityKeyPair = inMemoryStore.identityKeyPair
    override fun getLocalRegistrationId(): Int = inMemoryStore.localRegistrationId
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean =
        inMemoryStore.saveIdentity(address, identityKey)
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean = inMemoryStore.isTrustedIdentity(address, identityKey, direction)
    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        inMemoryStore.getIdentity(address)

    // PreKeyStore
    override fun loadPreKey(preKeyId: Int): PreKeyRecord = inMemoryStore.loadPreKey(preKeyId)
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = inMemoryStore.storePreKey(preKeyId, record)
    override fun containsPreKey(preKeyId: Int): Boolean = inMemoryStore.containsPreKey(preKeyId)
    override fun removePreKey(preKeyId: Int) = inMemoryStore.removePreKey(preKeyId)

    // SignedPreKeyStore
    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        inMemoryStore.loadSignedPreKey(signedPreKeyId)
    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = inMemoryStore.loadSignedPreKeys()
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
        inMemoryStore.storeSignedPreKey(signedPreKeyId, record)
    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        inMemoryStore.containsSignedPreKey(signedPreKeyId)
    override fun removeSignedPreKey(signedPreKeyId: Int) = inMemoryStore.removeSignedPreKey(signedPreKeyId)

    // SessionStore
    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        inMemoryStore.loadSession(address)
    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> =
        inMemoryStore.loadExistingSessions(addresses)
    override fun getSubDeviceSessions(name: String): MutableList<Int> =
        inMemoryStore.getSubDeviceSessions(name)
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
        inMemoryStore.storeSession(address, record)
    override fun containsSession(address: SignalProtocolAddress): Boolean =
        inMemoryStore.containsSession(address)
    override fun deleteSession(address: SignalProtocolAddress) = inMemoryStore.deleteSession(address)
    override fun deleteAllSessions(name: String) = inMemoryStore.deleteAllSessions(name)

    // SenderKeyStore
    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord
    ) = senderKeyStore.storeSenderKey(sender, distributionId, record)
    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID
    ): SenderKeyRecord = senderKeyStore.loadSenderKey(sender, distributionId)

    // KyberPreKeyStore
    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        kyberPreKeyStore.loadKyberPreKey(kyberPreKeyId)
    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = kyberPreKeyStore.loadKyberPreKeys()
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) =
        kyberPreKeyStore.storeKyberPreKey(kyberPreKeyId, record)
    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        kyberPreKeyStore.containsKyberPreKey(kyberPreKeyId)
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int) =
        kyberPreKeyStore.markKyberPreKeyUsed(kyberPreKeyId)
}
