package com.meshcipher.domain.model

import org.junit.Assert.*
import org.junit.Test

class MeshMessageTest {

    @Test
    fun `incrementHop increases hop count`() {
        val message = createMessage(hopCount = 0)
        val incremented = message.incrementHop("relay-1")

        assertEquals(1, incremented.hopCount)
    }

    @Test
    fun `incrementHop adds relay to path`() {
        val message = createMessage(path = listOf("origin"))
        val incremented = message.incrementHop("relay-1")

        assertEquals(listOf("origin", "relay-1"), incremented.path)
    }

    @Test
    fun `shouldRelay returns true when under TTL`() {
        val message = createMessage(hopCount = 3, ttl = 5)
        assertTrue(message.shouldRelay())
    }

    @Test
    fun `shouldRelay returns false when at TTL`() {
        val message = createMessage(hopCount = 5, ttl = 5)
        assertFalse(message.shouldRelay())
    }

    @Test
    fun `shouldRelay returns false when over TTL`() {
        val message = createMessage(hopCount = 6, ttl = 5)
        assertFalse(message.shouldRelay())
    }

    @Test
    fun `toBytes and fromBytes roundtrip preserves data`() {
        val original = MeshMessage(
            id = "test-msg-123",
            originDeviceId = "device-abc",
            originUserId = "user-sender",
            destinationUserId = "user-xyz",
            encryptedPayload = "hello world".toByteArray(),
            timestamp = 1700000000000L,
            ttl = 4,
            hopCount = 2,
            path = listOf("device-1", "device-2")
        )

        val bytes = original.toBytes()
        val restored = MeshMessage.fromBytes(bytes)

        assertNotNull(restored)
        assertEquals(original.id, restored!!.id)
        assertEquals(original.originDeviceId, restored.originDeviceId)
        assertEquals(original.originUserId, restored.originUserId)
        assertEquals(original.destinationUserId, restored.destinationUserId)
        assertArrayEquals(original.encryptedPayload, restored.encryptedPayload)
        assertEquals(original.timestamp, restored.timestamp)
        assertEquals(original.ttl, restored.ttl)
        assertEquals(original.hopCount, restored.hopCount)
        assertEquals(original.path, restored.path)
    }

    @Test
    fun `toBytes and fromBytes with empty path`() {
        val original = createMessage(path = emptyList())

        val bytes = original.toBytes()
        val restored = MeshMessage.fromBytes(bytes)

        assertNotNull(restored)
        assertTrue(restored!!.path.isEmpty())
    }

    @Test
    fun `fromBytes returns null for invalid data`() {
        assertNull(MeshMessage.fromBytes(ByteArray(5)))
    }

    @Test
    fun `equality based on id`() {
        val msg1 = createMessage(id = "same-id")
        val msg2 = createMessage(id = "same-id", hopCount = 3)

        assertEquals(msg1, msg2)
        assertEquals(msg1.hashCode(), msg2.hashCode())
    }

    @Test
    fun `inequality for different ids`() {
        val msg1 = createMessage(id = "id-1")
        val msg2 = createMessage(id = "id-2")

        assertNotEquals(msg1, msg2)
    }

    @Test
    fun `default TTL is MAX_TTL`() {
        val message = MeshMessage(
            id = "test",
            originDeviceId = "device",
            originUserId = "sender",
            destinationUserId = "user",
            encryptedPayload = ByteArray(0),
            timestamp = 0L
        )
        assertEquals(MeshMessage.MAX_TTL, message.ttl)
    }

    private fun createMessage(
        id: String = "test-msg",
        hopCount: Int = 0,
        ttl: Int = 5,
        path: List<String> = emptyList()
    ) = MeshMessage(
        id = id,
        originDeviceId = "origin-device",
        originUserId = "origin-user",
        destinationUserId = "dest-user",
        encryptedPayload = "test-data".toByteArray(),
        timestamp = System.currentTimeMillis(),
        ttl = ttl,
        hopCount = hopCount,
        path = path
    )
}
