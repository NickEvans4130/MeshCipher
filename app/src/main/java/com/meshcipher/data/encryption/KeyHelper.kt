package com.meshcipher.data.encryption

import org.signal.libsignal.protocol.util.KeyHelper as SignalKeyHelper
import java.security.SecureRandom

object KeyHelper {

    fun generateRegistrationId(): Int {
        return SignalKeyHelper.generateRegistrationId(false)
    }

    fun generateRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes
    }
}
