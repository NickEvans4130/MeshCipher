package com.meshcipher.data.encryption

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
    private val store: SignalProtocolStoreImpl
) {
    companion object {
        /** Kyber pre-key ID used for the current one-time key. Rotated on use. */
        private const val KYBER_PRE_KEY_ID = 1
        /** Signed pre-key ID used for the long-lived SPK. */
        private const val SIGNED_PRE_KEY_ID = 1
        /** One-time pre-key ID for classic X25519 pre-key. */
        private const val PRE_KEY_ID = 1
    }

    /**
     * RM-10: Ensures a Kyber-1024 pre-key exists in the store.
     * Called at startup and after a key is consumed.
     * The private key is stored in [SignalProtocolStoreImpl] (EncryptedSharedPreferences).
     */
    fun ensureKyberPreKey() {
        if (store.containsKyberPreKey(KYBER_PRE_KEY_ID)) {
            Timber.d("Kyber pre-key %d already exists", KYBER_PRE_KEY_ID)
            return
        }
        val identityKeyPair = store.getIdentityKeyPair()
        val kyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        // Sign the Kyber public key with our ED25519 identity key so recipients can
        // verify it wasn't tampered with during transit.
        val kyberPubKeyBytes = kyberKeyPair.publicKey.serialize()
        val signature = Curve.calculateSignature(identityKeyPair.privateKey, kyberPubKeyBytes)
        val record = KyberPreKeyRecord(
            KYBER_PRE_KEY_ID,
            System.currentTimeMillis(),
            kyberKeyPair,
            signature
        )
        store.storeKyberPreKey(KYBER_PRE_KEY_ID, record)
        Timber.d("Generated new Kyber-1024 pre-key (id=%d)", KYBER_PRE_KEY_ID)
    }

    /**
     * RM-10: Ensures a classic X25519 one-time pre-key and a signed pre-key exist.
     * Called at startup. Both are stored in [SignalProtocolStoreImpl].
     */
    fun ensureClassicPreKeys() {
        if (!store.containsPreKey(PRE_KEY_ID)) {
            val keyPair = Curve.generateKeyPair()
            val preKeyRecord = PreKeyRecord(PRE_KEY_ID, keyPair)
            store.storePreKey(PRE_KEY_ID, preKeyRecord)
            Timber.d("Generated one-time pre-key (id=%d)", PRE_KEY_ID)
        }
        if (!store.containsSignedPreKey(SIGNED_PRE_KEY_ID)) {
            val identityKeyPair = store.getIdentityKeyPair()
            val spkKeyPair = Curve.generateKeyPair()
            val spkPubKeyBytes = spkKeyPair.publicKey.serialize()
            val signature = Curve.calculateSignature(identityKeyPair.privateKey, spkPubKeyBytes)
            val spk = SignedPreKeyRecord(SIGNED_PRE_KEY_ID, System.currentTimeMillis(), spkKeyPair, signature)
            store.storeSignedPreKey(SIGNED_PRE_KEY_ID, spk)
            Timber.d("Generated signed pre-key (id=%d)", SIGNED_PRE_KEY_ID)
        }
    }

    /**
     * RM-10: Builds a PQXDH-capable [PreKeyBundle] for upload to the relay.
     * If Kyber keys are not available (should not happen after [ensureKyberPreKey]),
     * falls back to a classical X3DH bundle.
     */
    fun buildLocalBundle(): PreKeyBundle {
        ensureClassicPreKeys()
        ensureKyberPreKey()

        val identityKeyPair = store.getIdentityKeyPair()
        val regId = store.localRegistrationId
        val preKey = store.loadPreKey(PRE_KEY_ID)
        val spk = store.loadSignedPreKey(SIGNED_PRE_KEY_ID)

        return if (store.containsKyberPreKey(KYBER_PRE_KEY_ID)) {
            val kyberRecord = store.loadKyberPreKey(KYBER_PRE_KEY_ID)
            PreKeyBundle(
                regId, 1,
                PRE_KEY_ID, preKey.keyPair.publicKey,
                SIGNED_PRE_KEY_ID, spk.keyPair.publicKey, spk.signature,
                identityKeyPair.publicKey,
                kyberRecord.id, kyberRecord.keyPair.publicKey, kyberRecord.signature
            )
        } else {
            // RM-10 backwards compat: classical X3DH fallback
            Timber.w("No Kyber pre-key available — building classical X3DH bundle")
            PreKeyBundle(
                regId, 1,
                PRE_KEY_ID, preKey.keyPair.publicKey,
                SIGNED_PRE_KEY_ID, spk.keyPair.publicKey, spk.signature,
                identityKeyPair.publicKey
            )
        }
    }

    /**
     * RM-10: Builds a [PreKeyBundle] from data downloaded from the relay for a remote contact.
     * If [kyberPreKeyId] and [kyberPreKey] are null, builds a classical X3DH bundle,
     * allowing interoperability with clients that do not yet support PQXDH.
     */
    @Throws(org.signal.libsignal.protocol.InvalidKeyException::class)
    fun buildRemoteBundle(
        registrationId: Int,
        preKeyId: Int,
        preKeyBytes: ByteArray,
        signedPreKeyId: Int,
        signedPreKeyBytes: ByteArray,
        signedPreKeySignature: ByteArray,
        identityKeyBytes: ByteArray,
        kyberPreKeyId: Int? = null,
        kyberPreKeyBytes: ByteArray? = null,
        kyberPreKeySignature: ByteArray? = null
    ): PreKeyBundle {
        val preKey = Curve.decodePoint(preKeyBytes, 0)
        val signedPreKey = Curve.decodePoint(signedPreKeyBytes, 0)
        val identityKey = org.signal.libsignal.protocol.IdentityKey(identityKeyBytes, 0)

        return if (kyberPreKeyId != null && kyberPreKeyBytes != null && kyberPreKeySignature != null) {
            val kyberPublicKey = KEMPublicKey(kyberPreKeyBytes)
            // RM-10: PQXDH bundle — session key = KDF(X25519_output || Kyber_KEM_output)
            // The hybrid KDF is applied internally by libsignal's SessionBuilder.process().
            Timber.d("Building PQXDH bundle (Kyber pre-key id=%d)", kyberPreKeyId)
            PreKeyBundle(
                registrationId, 1,
                preKeyId, preKey,
                signedPreKeyId, signedPreKey, signedPreKeySignature,
                identityKey,
                kyberPreKeyId, kyberPublicKey, kyberPreKeySignature
            )
        } else {
            // RM-10 backwards compat: contact does not support PQXDH — use X3DH
            Timber.w("Remote bundle has no Kyber key — falling back to classical X3DH (session is not PQ-protected)")
            PreKeyBundle(
                registrationId, 1,
                preKeyId, preKey,
                signedPreKeyId, signedPreKey, signedPreKeySignature,
                identityKey
            )
        }
    }
}
