package com.meshcipher.data.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.*
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyManager @Inject constructor() {

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    companion object {
        private const val HARDWARE_KEY_ALIAS = "meshcipher_hardware_key"
    }

    fun generateHardwareKey(): PublicKey {
        if (keyStore.containsAlias(HARDWARE_KEY_ALIAS)) {
            return getPublicKey()
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore"
        )

        val spec = KeyGenParameterSpec.Builder(
            HARDWARE_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationValidityDurationSeconds(30)
            .setInvalidatedByBiometricEnrollment(true)
            .build()

        keyPairGenerator.initialize(spec)
        val keyPair = keyPairGenerator.generateKeyPair()

        return keyPair.public
    }

    fun getPublicKey(alias: String = HARDWARE_KEY_ALIAS): PublicKey {
        val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        return entry.certificate.publicKey
    }

    fun signWithHardwareKey(data: ByteArray): ByteArray {
        val entry = keyStore.getEntry(HARDWARE_KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        val privateKey = entry.privateKey

        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data)

        return signature.sign()
    }

    fun verifySignature(
        data: ByteArray,
        signature: ByteArray,
        publicKey: PublicKey
    ): Boolean {
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(data)
        return verifier.verify(signature)
    }

    fun hasHardwareKey(): Boolean {
        return keyStore.containsAlias(HARDWARE_KEY_ALIAS)
    }

    fun deleteHardwareKey() {
        if (keyStore.containsAlias(HARDWARE_KEY_ALIAS)) {
            keyStore.deleteEntry(HARDWARE_KEY_ALIAS)
        }
    }
}
