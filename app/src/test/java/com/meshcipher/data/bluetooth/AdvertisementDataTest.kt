package com.meshcipher.data.bluetooth

import org.junit.Assert.*
import org.junit.Test

class AdvertisementDataTest {

    @Test
    fun `toBytes produces correct packet size`() {
        val data = AdvertisementData(
            messageType = AdvertisementData.MessageType.BEACON,
            deviceId = "test-device-id",
            userId = "test-user-id",
            timestamp = System.currentTimeMillis(),
            signature = ByteArray(64)
        )

        val bytes = data.toBytes()
        assertEquals(AdvertisementData.PACKET_SIZE, bytes.size)
    }

    @Test
    fun `toBytes header contains version and message type`() {
        val data = AdvertisementData(
            messageType = AdvertisementData.MessageType.DATA,
            deviceId = "test-device",
            userId = "test-user",
            timestamp = System.currentTimeMillis(),
            signature = ByteArray(64)
        )

        val bytes = data.toBytes()
        assertEquals(AdvertisementData.PROTOCOL_VERSION, bytes[0])
        assertEquals(AdvertisementData.MessageType.DATA.value, bytes[1])
    }

    @Test
    fun `fromBytes returns null for too-short input`() {
        assertNull(AdvertisementData.fromBytes(ByteArray(10)))
    }

    @Test
    fun `fromBytes returns null for invalid message type`() {
        val bytes = ByteArray(130)
        bytes[0] = 0x01 // version
        bytes[1] = 0x99.toByte() // invalid type

        assertNull(AdvertisementData.fromBytes(bytes))
    }

    @Test
    fun `fromBytes parses valid BEACON packet`() {
        val bytes = ByteArray(130)
        bytes[0] = 0x01 // version
        bytes[1] = 0x01 // BEACON

        val result = AdvertisementData.fromBytes(bytes)
        assertNotNull(result)
        assertEquals(AdvertisementData.MessageType.BEACON, result!!.messageType)
    }

    @Test
    fun `fromBytes parses all message types`() {
        for (type in AdvertisementData.MessageType.entries) {
            val bytes = ByteArray(130)
            bytes[0] = 0x01
            bytes[1] = type.value

            val result = AdvertisementData.fromBytes(bytes)
            assertNotNull(result)
            assertEquals(type, result!!.messageType)
        }
    }

    @Test
    fun `roundtrip preserves message type`() {
        val original = AdvertisementData(
            messageType = AdvertisementData.MessageType.ROUTE_UPDATE,
            deviceId = "device-123",
            userId = "user-456",
            timestamp = System.currentTimeMillis(),
            signature = ByteArray(64) { it.toByte() }
        )

        val bytes = original.toBytes()
        val parsed = AdvertisementData.fromBytes(bytes)

        assertNotNull(parsed)
        assertEquals(original.messageType, parsed!!.messageType)
        assertEquals(original.protocolVersion, parsed.protocolVersion)
    }

    @Test
    fun `signature shorter than 64 bytes is padded`() {
        val data = AdvertisementData(
            messageType = AdvertisementData.MessageType.BEACON,
            deviceId = "test",
            userId = "test",
            timestamp = System.currentTimeMillis(),
            signature = ByteArray(10)
        )

        val bytes = data.toBytes()
        assertEquals(AdvertisementData.PACKET_SIZE, bytes.size)
    }
}
