package com.meshcipher.data.encryption

import org.junit.Assert.*
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.ecc.Curve
import java.util.Base64

/**
 * Tests that Signal Protocol record serialization/deserialization round-trips correctly.
 * These are the same operations performed by EncryptedSignalProtocolStoreImpl
 * when persisting to EncryptedSharedPreferences.
 */
class SignalProtocolSerializationTest {

    @Test
    fun `IdentityKeyPair serialization round trip`() {
        val original = IdentityKeyPair.generate()
        val serialized = original.serialize()
        val encoded = Base64.getEncoder().encodeToString(serialized)
        val decoded = Base64.getDecoder().decode(encoded)
        val restored = IdentityKeyPair(decoded)

        assertArrayEquals(original.publicKey.serialize(), restored.publicKey.serialize())
        assertArrayEquals(original.serialize(), restored.serialize())
    }

    @Test
    fun `IdentityKey serialization round trip`() {
        val keyPair = IdentityKeyPair.generate()
        val original = keyPair.publicKey
        val serialized = original.serialize()
        val encoded = Base64.getEncoder().encodeToString(serialized)
        val decoded = Base64.getDecoder().decode(encoded)
        val restored = IdentityKey(decoded)

        assertEquals(original, restored)
    }

    @Test
    fun `PreKeyRecord serialization round trip`() {
        val keyPair = Curve.generateKeyPair()
        val original = PreKeyRecord(42, keyPair)
        val serialized = original.serialize()
        val encoded = Base64.getEncoder().encodeToString(serialized)
        val decoded = Base64.getDecoder().decode(encoded)
        val restored = PreKeyRecord(decoded)

        assertEquals(original.id, restored.id)
        assertArrayEquals(
            original.keyPair.publicKey.serialize(),
            restored.keyPair.publicKey.serialize()
        )
    }

    @Test
    fun `SessionRecord serialization round trip`() {
        val original = SessionRecord()
        val serialized = original.serialize()
        val encoded = Base64.getEncoder().encodeToString(serialized)
        val decoded = Base64.getDecoder().decode(encoded)
        val restored = SessionRecord(decoded)

        assertArrayEquals(original.serialize(), restored.serialize())
    }

    @Test
    fun `SignedPreKeyRecord serialization round trip`() {
        val identityKeyPair = IdentityKeyPair.generate()
        val keyPair = Curve.generateKeyPair()
        val signature = Curve.calculateSignature(
            identityKeyPair.privateKey,
            keyPair.publicKey.serialize()
        )
        val original = SignedPreKeyRecord(1, System.currentTimeMillis(), keyPair, signature)
        val serialized = original.serialize()
        val encoded = Base64.getEncoder().encodeToString(serialized)
        val decoded = Base64.getDecoder().decode(encoded)
        val restored = SignedPreKeyRecord(decoded)

        assertEquals(original.id, restored.id)
        assertArrayEquals(
            original.keyPair.publicKey.serialize(),
            restored.keyPair.publicKey.serialize()
        )
    }

    @Test
    fun `Base64 encoding preserves binary data integrity`() {
        // Simulate a large serialized record with all byte values
        val original = ByteArray(256) { it.toByte() }
        val encoded = Base64.getEncoder().encodeToString(original)
        val decoded = Base64.getDecoder().decode(encoded)
        assertArrayEquals(original, decoded)
    }
}
