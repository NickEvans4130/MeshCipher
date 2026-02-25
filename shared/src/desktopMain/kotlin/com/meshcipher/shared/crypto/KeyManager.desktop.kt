package com.meshcipher.shared.crypto

import java.io.File
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

actual class KeyManager actual constructor() {

    private val configDir = File(
        System.getProperty("user.home"),
        ".config/meshcipher"
    ).also { it.mkdirs() }

    private val publicKeyFile = File(configDir, "identity.pub")
    private val privateKeyFile = File(configDir, "identity.key.enc")
    private val wrapKeyFile = File(configDir, "wrap.key")

    actual fun generateHardwareKey(): ByteArray {
        if (hasHardwareKey()) return getPublicKey()

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val keyPair = kpg.generateKeyPair()

        // Persist public key
        publicKeyFile.writeBytes(keyPair.public.encoded)

        // Encrypt and persist private key using a local AES-256-GCM wrap key
        val wrapKey = getOrCreateWrapKey()
        val encryptedPrivate = aesGcmEncrypt(keyPair.private.encoded, wrapKey)
        privateKeyFile.writeBytes(encryptedPrivate)

        return keyPair.public.encoded
    }

    actual fun getPublicKey(): ByteArray {
        check(publicKeyFile.exists()) { "No hardware key found" }
        return publicKeyFile.readBytes()
    }

    actual fun signWithHardwareKey(data: ByteArray): ByteArray {
        val privateKey = loadPrivateKey()
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(data)
        return signer.sign()
    }

    actual fun hasHardwareKey(): Boolean =
        publicKeyFile.exists() && privateKeyFile.exists()

    actual fun deleteHardwareKey() {
        publicKeyFile.delete()
        privateKeyFile.delete()
        wrapKeyFile.delete()
    }

    // --- Helpers ---

    private fun loadPrivateKey(): PrivateKey {
        check(privateKeyFile.exists()) { "No private key found" }
        val wrapKey = getOrCreateWrapKey()
        val decrypted = aesGcmDecrypt(privateKeyFile.readBytes(), wrapKey)
        val spec = PKCS8EncodedKeySpec(decrypted)
        return java.security.KeyFactory.getInstance("EC").generatePrivate(spec)
    }

    private fun getOrCreateWrapKey(): ByteArray {
        if (wrapKeyFile.exists()) return wrapKeyFile.readBytes()
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256, SecureRandom())
        val key = kg.generateKey().encoded
        wrapKeyFile.writeBytes(key)
        return key
    }

    private fun aesGcmEncrypt(plaintext: ByteArray, keyBytes: ByteArray): ByteArray {
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext // 12-byte IV prepended
    }

    private fun aesGcmDecrypt(ivAndCiphertext: ByteArray, keyBytes: ByteArray): ByteArray {
        val key = SecretKeySpec(keyBytes, "AES")
        val iv = ivAndCiphertext.copyOf(12)
        val ciphertext = ivAndCiphertext.copyOfRange(12, ivAndCiphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }
}
