package com.meshcipher.shared.crypto

import java.io.File
import java.security.KeyFactory
import javax.crypto.KeyAgreement
import java.security.KeyPairGenerator
import java.security.MessageDigest
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
        // Also remove from libsecret if present
        try {
            ProcessBuilder(
                "secret-tool", "clear",
                "application", "meshcipher",
                "key-type", "wrap"
            ).start().waitFor()
        } catch (_: Exception) { }
    }

    /**
     * Perform ECDH key agreement with the remote party's public key.
     * Returns a 32-byte shared secret (SHA-256 of the raw ECDH output).
     *
     * Both sides compute the same secret: ECDH(our_priv, their_pub) == ECDH(their_priv, our_pub).
     * This is used as the AES-256-GCM session key in MessageCrypto.
     *
     * Note: no forward secrecy (no ephemeral keys). Full Signal Protocol X3DH
     * is the planned upgrade for production.
     */
    fun performECDH(theirPublicKeyBytes: ByteArray): ByteArray {
        val privateKey = loadPrivateKey()
        val theirPublicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(theirPublicKeyBytes))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(theirPublicKey, true)
        return MessageDigest.getInstance("SHA-256").digest(ka.generateSecret())
    }

    // --- Helpers ---

    private fun loadPrivateKey(): PrivateKey {
        check(privateKeyFile.exists()) { "No private key found" }
        val wrapKey = getOrCreateWrapKey()
        val decrypted = aesGcmDecrypt(privateKeyFile.readBytes(), wrapKey)
        val spec = PKCS8EncodedKeySpec(decrypted)
        return java.security.KeyFactory.getInstance("EC").generatePrivate(spec)
    }

    /**
     * Retrieve or generate the AES-256 wrap key used to encrypt the private key file.
     *
     * Storage priority:
     * 1. OS keyring via `secret-tool` (GNOME Keyring / KWallet / libsecret)
     * 2. Plaintext wrap.key file as fallback (for systems without libsecret)
     *
     * On first run the key is generated and stored in the best available backend.
     * If libsecret becomes available later, the key is migrated automatically.
     */
    private fun getOrCreateWrapKey(): ByteArray {
        // 1. Try to load from libsecret
        loadFromLibsecret()?.let { return it }

        // 2. Fall back to plaintext file (legacy or no libsecret)
        if (wrapKeyFile.exists()) {
            val key = wrapKeyFile.readBytes()
            // Opportunistic migration: store in libsecret and delete file
            if (storeInLibsecret(key)) {
                wrapKeyFile.delete()
            }
            return key
        }

        // 3. Generate a new key
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256, SecureRandom())
        val key = kg.generateKey().encoded

        if (!storeInLibsecret(key)) {
            // libsecret unavailable — use plaintext file
            wrapKeyFile.writeBytes(key)
        }
        return key
    }

    /**
     * Store [keyBytes] in the OS keyring via `secret-tool`.
     * Returns true if stored successfully.
     */
    private fun storeInLibsecret(keyBytes: ByteArray): Boolean = try {
        val pb = ProcessBuilder(
            "secret-tool", "store",
            "--label=MeshCipher identity wrap key",
            "application", "meshcipher",
            "key-type", "wrap"
        )
        pb.redirectErrorStream(true)
        val proc = pb.start()
        proc.outputStream.use { it.write(keyBytes) }
        proc.waitFor() == 0
    } catch (_: Exception) { false }

    /**
     * Retrieve the wrap key from the OS keyring.
     * Returns null if not found or libsecret unavailable.
     */
    private fun loadFromLibsecret(): ByteArray? = try {
        val pb = ProcessBuilder(
            "secret-tool", "lookup",
            "application", "meshcipher",
            "key-type", "wrap"
        )
        pb.redirectErrorStream(false)
        val proc = pb.start()
        val bytes = proc.inputStream.readBytes()
        val exit = proc.waitFor()
        if (exit == 0 && bytes.isNotEmpty()) bytes else null
    } catch (_: Exception) { null }

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
