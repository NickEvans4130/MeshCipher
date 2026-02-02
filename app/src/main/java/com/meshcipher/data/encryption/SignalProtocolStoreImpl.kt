package com.meshcipher.data.encryption

import android.content.Context
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.state.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignalProtocolStoreImpl @Inject constructor(
    private val context: Context
) : SignalProtocolStore {

    private val identityKeyStore = InMemoryIdentityKeyStore()
    private val preKeyStore = InMemoryPreKeyStore()
    private val signedPreKeyStore = InMemorySignedPreKeyStore()
    private val sessionStore = InMemorySessionStore()

    override fun getIdentityKeyPair(): IdentityKeyPair {
        return identityKeyStore.identityKeyPair
    }

    override fun getLocalRegistrationId(): Int {
        return identityKeyStore.localRegistrationId
    }

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        return identityKeyStore.saveIdentity(address, identityKey)
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        return identityKeyStore.isTrustedIdentity(address, identityKey, direction)
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        return identityKeyStore.getIdentity(address)
    }

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        return preKeyStore.loadPreKey(preKeyId)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        preKeyStore.storePreKey(preKeyId, record)
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        return preKeyStore.containsPreKey(preKeyId)
    }

    override fun removePreKey(preKeyId: Int) {
        preKeyStore.removePreKey(preKeyId)
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        return signedPreKeyStore.loadSignedPreKey(signedPreKeyId)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        return signedPreKeyStore.loadSignedPreKeys()
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        signedPreKeyStore.storeSignedPreKey(signedPreKeyId, record)
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        return signedPreKeyStore.containsSignedPreKey(signedPreKeyId)
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        signedPreKeyStore.removeSignedPreKey(signedPreKeyId)
    }

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        return sessionStore.loadSession(address)
    }

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> {
        return sessionStore.loadExistingSessions(addresses)
    }

    override fun getSubDeviceSessions(name: String): MutableList<Int> {
        return sessionStore.getSubDeviceSessions(name)
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        sessionStore.storeSession(address, record)
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        return sessionStore.containsSession(address)
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        sessionStore.deleteSession(address)
    }

    override fun deleteAllSessions(name: String) {
        sessionStore.deleteAllSessions(name)
    }
}
