package com.meshcipher.data.encryption

import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper as SignalKeyHelper
import java.security.SecureRandom

object KeyHelper {

    fun generateRegistrationId(): Int {
        return SignalKeyHelper.generateRegistrationId(false)
    }

    fun generatePreKeys(start: Int, count: Int): List<PreKeyRecord> {
        return SignalKeyHelper.generatePreKeys(start, count)
    }

    fun generateRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes
    }
}
