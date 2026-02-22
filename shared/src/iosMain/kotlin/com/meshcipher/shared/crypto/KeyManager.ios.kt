package com.meshcipher.shared.crypto

actual class KeyManager actual constructor() {

    actual fun generateHardwareKey(): ByteArray =
        throw NotImplementedError("KeyManager not yet implemented on iOS")

    actual fun getPublicKey(): ByteArray =
        throw NotImplementedError("KeyManager not yet implemented on iOS")

    actual fun signWithHardwareKey(data: ByteArray): ByteArray =
        throw NotImplementedError("KeyManager not yet implemented on iOS")

    actual fun hasHardwareKey(): Boolean = false

    actual fun deleteHardwareKey() = Unit
}
