package com.meshcipher.shared.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

actual class KeyManager actual constructor() {

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val HARDWARE_KEY_ALIAS = "meshcipher_hardware_key"
    }

    actual fun generateHardwareKey(): ByteArray {
        if (keyStore.containsAlias(HARDWARE_KEY_ALIAS)) return getPublicKey()

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
        return keyPairGenerator.generateKeyPair().public.encoded
    }

    actual fun getPublicKey(): ByteArray {
        val entry = keyStore.getEntry(HARDWARE_KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.certificate.publicKey.encoded
    }

    actual fun signWithHardwareKey(data: ByteArray): ByteArray {
        val entry = keyStore.getEntry(HARDWARE_KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(entry.privateKey)
        signature.update(data)
        return signature.sign()
    }

    actual fun hasHardwareKey(): Boolean = keyStore.containsAlias(HARDWARE_KEY_ALIAS)

    actual fun deleteHardwareKey() {
        if (keyStore.containsAlias(HARDWARE_KEY_ALIAS)) keyStore.deleteEntry(HARDWARE_KEY_ALIAS)
    }
}
