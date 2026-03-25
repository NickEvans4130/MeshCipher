package com.meshcipher.data.encryption

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RM-10 / GAP-08 / R-09: Generates and manages Kyber-1024 pre-keys for PQXDH.
 *
 * Works alongside [SignalProtocolStoreImpl] which already implements [KyberPreKeyStore].
 * The combined session key derivation (X25519 || Kyber KEM) is handled internally by
 * libsignal when [SessionBuilder.process] is called with a bundle that includes Kyber keys.
 *
 * Backwards compatibility: if a contact's pre-key bundle does not include a Kyber key,
 * [createSession] falls back to classical X3DH (existing behaviour).
 */
@Singleton
class PreKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SignalProtocolStoreImpl
) {
    companion object {
        /** Signed pre-key ID — long-lived, intentionally stable. */
        private const val SIGNED_PRE_KEY_ID = 1

        // SharedPreferences keys for persisting current active pre-key IDs.
        private const val PREFS_NAME = "pre_key_manager_prefs"
        private const val KEY_KYBER_ID = "current_kyber_pre_key_id"
        private const val KEY_PRE_KEY_ID = "current_pre_key_id"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Returns a unique pre-key ID not currently in use in the store, then persists it. */
    private fun nextPreKeyId(storeContainsFn: (Int) -> Boolean, prefsKey: String): Int {
        val rng = java.security.SecureRandom()
        var id: Int
        do {
            id = (rng.nextInt(0xFFFFFE) + 1) // 1..0xFFFFFF
        } while (storeContainsFn(id))
        prefs.edit().putInt(prefsKey, id).apply()
        return id
    }

    /** Returns the persisted active Kyber pre-key ID, or generates a new unique one. */
    private fun activeKyberPreKeyId(): Int {
        val stored = prefs.getInt(KEY_KYBER_ID, 0)
        return if (stored > 0 && store.containsKyberPreKey(stored)) stored
        else nextPreKeyId({ store.containsKyberPreKey(it) }, KEY_KYBER_ID)
    }

    /** Returns the persisted active X25519 one-time pre-key ID, or generates a new unique one. */
    private fun activePreKeyId(): Int {
        val stored = prefs.getInt(KEY_PRE_KEY_ID, 0)
        return if (stored > 0 && store.containsPreKey(stored)) stored
        else nextPreKeyId({ store.containsPreKey(it) }, KEY_PRE_KEY_ID)
    }

    /**
     * RM-10: Ensures a Kyber-1024 pre-key exists in the store.
     * Uses a unique ID on each rotation rather than a fixed constant.
     */
    fun ensureKyberPreKey() {
        val existingId = prefs.getInt(KEY_KYBER_ID, 0)
        if (existingId > 0 && store.containsKyberPreKey(existingId)) {
            Timber.d("Kyber pre-key %d already exists", existingId)
            return
        }
        val newId = nextPreKeyId({ store.containsKyberPreKey(it) }, KEY_KYBER_ID)
        val identityKeyPair = store.getIdentityKeyPair()
        val kyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberPubKeyBytes = kyberKeyPair.publicKey.serialize()
        val signature = Curve.calculateSignature(identityKeyPair.privateKey, kyberPubKeyBytes)
        val record = KyberPreKeyRecord(newId, System.currentTimeMillis(), kyberKeyPair, signature)
        store.storeKyberPreKey(newId, record)
        Timber.d("Generated new Kyber-1024 pre-key (id=%d)", newId)
    }

    /**
     * RM-10: Ensures a classic X25519 one-time pre-key and a signed pre-key exist.
     * One-time pre-key uses a unique ID on each rotation.
     */
    fun ensureClassicPreKeys() {
        val existingPreKeyId = prefs.getInt(KEY_PRE_KEY_ID, 0)
        if (existingPreKeyId == 0 || !store.containsPreKey(existingPreKeyId)) {
            val newId = nextPreKeyId({ store.containsPreKey(it) }, KEY_PRE_KEY_ID)
            val keyPair = Curve.generateKeyPair()
            store.storePreKey(newId, PreKeyRecord(newId, keyPair))
            Timber.d("Generated one-time pre-key (id=%d)", newId)
        }
        if (!store.containsSignedPreKey(SIGNED_PRE_KEY_ID)) {
            val identityKeyPair = store.getIdentityKeyPair()
            val spkKeyPair = Curve.generateKeyPair()
            val spkPubKeyBytes = spkKeyPair.publicKey.serialize()
            val signature = Curve.calculateSignature(identityKeyPair.privateKey, spkPubKeyBytes)
            store.storeSignedPreKey(SIGNED_PRE_KEY_ID, SignedPreKeyRecord(SIGNED_PRE_KEY_ID, System.currentTimeMillis(), spkKeyPair, signature))
            Timber.d("Generated signed pre-key (id=%d)", SIGNED_PRE_KEY_ID)
        }
    }

    /**
     * RM-10: Builds a PQXDH-capable [PreKeyBundle] for upload to the relay.
     * If Kyber keys are not available, falls back to a classical X3DH bundle.
     */
    fun buildLocalBundle(): PreKeyBundle {
        ensureClassicPreKeys()
        ensureKyberPreKey()

        val identityKeyPair = store.getIdentityKeyPair()
        val regId = store.localRegistrationId
        val preKeyId = activePreKeyId()
        val kyberKeyId = activeKyberPreKeyId()
        val preKey = store.loadPreKey(preKeyId)
        val spk = store.loadSignedPreKey(SIGNED_PRE_KEY_ID)

        return if (store.containsKyberPreKey(kyberKeyId)) {
            val kyberRecord = store.loadKyberPreKey(kyberKeyId)
            PreKeyBundle(
                regId, 1,
                preKeyId, preKey.keyPair.publicKey,
                SIGNED_PRE_KEY_ID, spk.keyPair.publicKey, spk.signature,
                identityKeyPair.publicKey,
                kyberRecord.id, kyberRecord.keyPair.publicKey, kyberRecord.signature
            )
        } else {
            Timber.w("No Kyber pre-key available — building classical X3DH bundle")
            PreKeyBundle(
                regId, 1,
                preKeyId, preKey.keyPair.publicKey,
                SIGNED_PRE_KEY_ID, spk.keyPair.publicKey, spk.signature,
                identityKeyPair.publicKey
            )
        }
    }

    /**
     * RM-10: Builds a [PreKeyBundle] from data downloaded from the relay for a remote contact.
     *
     * [pqxdhSupported] must be set based on the relay's `pqxdh_supported` flag.
     * - If true, all three Kyber fields must be non-null; a partial set is rejected.
     * - If false, all Kyber fields must be null; a classical X3DH bundle is returned.
     *
     * Partial Kyber payloads (one or two fields present) are always rejected to prevent
     * silent downgrade to classical X3DH through payload stripping.
     */
    @Throws(org.signal.libsignal.protocol.InvalidKeyException::class, IllegalArgumentException::class)
    fun buildRemoteBundle(
        registrationId: Int,
        preKeyId: Int,
        preKeyBytes: ByteArray,
        signedPreKeyId: Int,
        signedPreKeyBytes: ByteArray,
        signedPreKeySignature: ByteArray,
        identityKeyBytes: ByteArray,
        pqxdhSupported: Boolean,
        kyberPreKeyId: Int? = null,
        kyberPreKeyBytes: ByteArray? = null,
        kyberPreKeySignature: ByteArray? = null
    ): PreKeyBundle {
        val kyberFieldCount = listOfNotNull(
            kyberPreKeyId?.let { 1 },
            kyberPreKeyBytes?.let { 1 },
            kyberPreKeySignature?.let { 1 }
        ).size
        if (kyberFieldCount > 0 && kyberFieldCount < 3) {
            throw IllegalArgumentException("Partial Kyber payload rejected: $kyberFieldCount/3 fields present — possible downgrade attack")
        }
        if (pqxdhSupported && kyberFieldCount == 0) {
            throw IllegalArgumentException("Relay advertised PQXDH support but Kyber fields are absent — rejecting bundle")
        }

        val preKey = Curve.decodePoint(preKeyBytes, 0)
        val signedPreKey = Curve.decodePoint(signedPreKeyBytes, 0)
        val identityKey = org.signal.libsignal.protocol.IdentityKey(identityKeyBytes, 0)

        return if (pqxdhSupported && kyberPreKeyId != null && kyberPreKeyBytes != null && kyberPreKeySignature != null) {
            val kyberPublicKey = KEMPublicKey(kyberPreKeyBytes)
            Timber.d("Building PQXDH bundle (Kyber pre-key id=%d)", kyberPreKeyId)
            PreKeyBundle(
                registrationId, 1,
                preKeyId, preKey,
                signedPreKeyId, signedPreKey, signedPreKeySignature,
                identityKey,
                kyberPreKeyId, kyberPublicKey, kyberPreKeySignature
            )
        } else {
            Timber.w("Remote bundle has no Kyber key — using classical X3DH (session is not PQ-protected)")
            PreKeyBundle(
                registrationId, 1,
                preKeyId, preKey,
                signedPreKeyId, signedPreKey, signedPreKeySignature,
                identityKey
            )
        }
    }
}
