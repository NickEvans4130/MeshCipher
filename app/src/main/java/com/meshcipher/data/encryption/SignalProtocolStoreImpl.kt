package com.meshcipher.data.encryption

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.*
import org.signal.libsignal.protocol.util.KeyHelper as SignalKeyHelper
import timber.log.Timber
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent, encrypted Signal Protocol store.
 *
 * All sessions, pre-keys, signed pre-keys, identity keys, sender keys, and Kyber pre-keys
 * are stored in EncryptedSharedPreferences (AES-256-GCM) and survive app restarts.
 * The identity key pair and registration ID are generated once on first launch and
 * persisted thereafter.
 */
@Singleton
class SignalProtocolStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SignalProtocolStore {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val identityKeyPairCached: IdentityKeyPair
    private val registrationIdCached: Int

    init {
        val existingKeyPair = prefs.getString(KEY_IDENTITY_KEY_PAIR, null)
        val existingRegId = prefs.getInt(KEY_REGISTRATION_ID, -1)

        if (existingKeyPair != null && existingRegId != -1) {
            identityKeyPairCached = IdentityKeyPair(Base64.getDecoder().decode(existingKeyPair))
            registrationIdCached = existingRegId
            Timber.d("Loaded existing Signal identity (regId=%d)", registrationIdCached)
        } else {
            identityKeyPairCached = IdentityKeyPair.generate()
            registrationIdCached = SignalKeyHelper.generateRegistrationId(false)
            prefs.edit()
                .putString(KEY_IDENTITY_KEY_PAIR, Base64.getEncoder().encodeToString(identityKeyPairCached.serialize()))
                .putInt(KEY_REGISTRATION_ID, registrationIdCached)
                .apply()
            Timber.d("Generated new Signal identity (regId=%d)", registrationIdCached)
        }
    }

    // -- IdentityKeyStore --

    override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPairCached

    override fun getLocalRegistrationId(): Int = registrationIdCached

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        val key = "$PREFIX_IDENTITY${address.name}:${address.deviceId}"
        val existing = prefs.getString(key, null)
        val encoded = Base64.getEncoder().encodeToString(identityKey.serialize())
        prefs.edit().putString(key, encoded).apply()
        return existing != null && existing != encoded
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        val key = "$PREFIX_IDENTITY${address.name}:${address.deviceId}"
        val existing = prefs.getString(key, null) ?: return true // Trust on first use
        val existingKey = IdentityKey(Base64.getDecoder().decode(existing))
        return existingKey == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val key = "$PREFIX_IDENTITY${address.name}:${address.deviceId}"
        val encoded = prefs.getString(key, null) ?: return null
        return IdentityKey(Base64.getDecoder().decode(encoded))
    }

    // -- PreKeyStore --

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val key = "$PREFIX_PREKEY$preKeyId"
        val encoded = prefs.getString(key, null)
            ?: throw InvalidKeyIdException("No pre-key: $preKeyId")
        return PreKeyRecord(Base64.getDecoder().decode(encoded))
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        prefs.edit().putString("$PREFIX_PREKEY$preKeyId",
            Base64.getEncoder().encodeToString(record.serialize())).apply()
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        return prefs.contains("$PREFIX_PREKEY$preKeyId")
    }

    override fun removePreKey(preKeyId: Int) {
        prefs.edit().remove("$PREFIX_PREKEY$preKeyId").apply()
    }

    // -- SignedPreKeyStore --

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val key = "$PREFIX_SIGNED_PREKEY$signedPreKeyId"
        val encoded = prefs.getString(key, null)
            ?: throw InvalidKeyIdException("No signed pre-key: $signedPreKeyId")
        return SignedPreKeyRecord(Base64.getDecoder().decode(encoded))
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        return prefs.all.entries
            .filter { it.key.startsWith(PREFIX_SIGNED_PREKEY) }
            .mapNotNull { entry ->
                try {
                    SignedPreKeyRecord(Base64.getDecoder().decode(entry.value as String))
                } catch (e: Exception) {
                    Timber.w(e, "Failed to load signed pre-key: %s", entry.key)
                    null
                }
            }
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        prefs.edit().putString("$PREFIX_SIGNED_PREKEY$signedPreKeyId",
            Base64.getEncoder().encodeToString(record.serialize())).apply()
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        return prefs.contains("$PREFIX_SIGNED_PREKEY$signedPreKeyId")
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        prefs.edit().remove("$PREFIX_SIGNED_PREKEY$signedPreKeyId").apply()
    }

    // -- SessionStore --

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val key = "$PREFIX_SESSION${address.name}:${address.deviceId}"
        val encoded = prefs.getString(key, null) ?: return SessionRecord()
        return SessionRecord(Base64.getDecoder().decode(encoded))
    }

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> {
        return addresses.map { address ->
            val key = "$PREFIX_SESSION${address.name}:${address.deviceId}"
            val encoded = prefs.getString(key, null)
                ?: throw NoSessionException("No session for: $address")
            SessionRecord(Base64.getDecoder().decode(encoded))
        }.toMutableList()
    }

    override fun getSubDeviceSessions(name: String): MutableList<Int> {
        val prefix = "${PREFIX_SESSION}$name:"
        return prefs.all.keys
            .filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).toIntOrNull() }
            .filter { it != 1 }
            .toMutableList()
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        val key = "$PREFIX_SESSION${address.name}:${address.deviceId}"
        prefs.edit().putString(key,
            Base64.getEncoder().encodeToString(record.serialize())).apply()
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        return prefs.contains("$PREFIX_SESSION${address.name}:${address.deviceId}")
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        prefs.edit().remove("$PREFIX_SESSION${address.name}:${address.deviceId}").apply()
    }

    override fun deleteAllSessions(name: String) {
        val prefix = "${PREFIX_SESSION}$name:"
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    // -- SenderKeyStore --

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord
    ) {
        val key = "$PREFIX_SENDER_KEY${sender.name}:${sender.deviceId}:$distributionId"
        prefs.edit().putString(key,
            Base64.getEncoder().encodeToString(record.serialize())).apply()
    }

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID
    ): SenderKeyRecord {
        val key = "$PREFIX_SENDER_KEY${sender.name}:${sender.deviceId}:$distributionId"
        val encoded = prefs.getString(key, null) ?: return SenderKeyRecord(0)
        return SenderKeyRecord(Base64.getDecoder().decode(encoded))
    }

    // -- KyberPreKeyStore --

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val key = "$PREFIX_KYBER_PREKEY$kyberPreKeyId"
        val encoded = prefs.getString(key, null)
            ?: throw InvalidKeyIdException("No Kyber pre-key: $kyberPreKeyId")
        return KyberPreKeyRecord(Base64.getDecoder().decode(encoded))
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
        return prefs.all.entries
            .filter { it.key.startsWith(PREFIX_KYBER_PREKEY) }
            .mapNotNull { entry ->
                try {
                    KyberPreKeyRecord(Base64.getDecoder().decode(entry.value as String))
                } catch (e: Exception) {
                    Timber.w(e, "Failed to load Kyber pre-key: %s", entry.key)
                    null
                }
            }
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        prefs.edit().putString("$PREFIX_KYBER_PREKEY$kyberPreKeyId",
            Base64.getEncoder().encodeToString(record.serialize())).apply()
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean {
        return prefs.contains("$PREFIX_KYBER_PREKEY$kyberPreKeyId")
    }

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int) {
        // Kyber pre-keys are one-time use; remove after use
        prefs.edit().remove("$PREFIX_KYBER_PREKEY$kyberPreKeyId").apply()
    }

    companion object {
        private const val PREFS_NAME = "signal_protocol_store"
        private const val KEY_IDENTITY_KEY_PAIR = "identity_key_pair"
        private const val KEY_REGISTRATION_ID = "registration_id"
        private const val PREFIX_IDENTITY = "identity:"
        private const val PREFIX_PREKEY = "prekey:"
        private const val PREFIX_SIGNED_PREKEY = "signed_prekey:"
        private const val PREFIX_SESSION = "session:"
        private const val PREFIX_SENDER_KEY = "sender_key:"
        private const val PREFIX_KYBER_PREKEY = "kyber_prekey:"
    }
}
