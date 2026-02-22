package com.meshcipher.shared.crypto

/**
 * Platform-specific key management.
 * Android uses AndroidKeyStore hardware-backed keys.
 * iOS uses Secure Enclave / Keychain.
 */
expect class KeyManager() {
    fun generateHardwareKey(): ByteArray
    fun getPublicKey(): ByteArray
    fun signWithHardwareKey(data: ByteArray): ByteArray
    fun hasHardwareKey(): Boolean
    fun deleteHardwareKey()
}
