package com.meshcipher.data.tor

import com.meshcipher.domain.model.P2PMessage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HiddenServiceServerTest {

    private lateinit var server: HiddenServiceServer

    @Before
    fun setup() {
        server = HiddenServiceServer()
    }

    @Test
    fun `port is 0 when not started`() {
        assertEquals(0, server.port)
    }

    @Test
    fun `start returns a valid port`() {
        val port = server.start()
        assertTrue(port > 0)
        assertEquals(port, server.port)
        server.stop()
    }

    @Test
    fun `stop resets port to 0`() {
        server.start()
        server.stop()
        assertEquals(0, server.port)
    }

    @Test
    fun `P2PMessage serialization round trip`() {
        val original = P2PMessage(
            type = P2PMessage.Type.TEXT,
            messageId = "msg-123",
            senderId = "sender-abc",
            recipientId = "recipient-xyz",
            timestamp = 1700000000000L,
            payload = "SGVsbG8gV29ybGQ="
        )

        val json = original.toJson()
        val parsed = P2PMessage.fromJson(json)

        assertEquals(original.type, parsed.type)
        assertEquals(original.messageId, parsed.messageId)
        assertEquals(original.senderId, parsed.senderId)
        assertEquals(original.recipientId, parsed.recipientId)
        assertEquals(original.timestamp, parsed.timestamp)
        assertEquals(original.payload, parsed.payload)
    }

    @Test
    fun `P2PMessage ACK has no payload`() {
        val ack = P2PMessage(
            type = P2PMessage.Type.ACK,
            messageId = "msg-123",
            senderId = "sender",
            recipientId = "recipient",
            timestamp = System.currentTimeMillis()
        )

        val json = ack.toJson()
        val parsed = P2PMessage.fromJson(json)

        assertEquals(P2PMessage.Type.ACK, parsed.type)
        assertNull(parsed.payload)
    }

    @Test
    fun `P2PMessage wire format write and read`() {
        val message = P2PMessage(
            type = P2PMessage.Type.PING,
            messageId = "ping-1",
            senderId = "a",
            recipientId = "b",
            timestamp = 123456789L
        )

        val baos = java.io.ByteArrayOutputStream()
        message.writeToStream(baos)

        val bais = java.io.ByteArrayInputStream(baos.toByteArray())
        val read = P2PMessage.readFromStream(bais)

        assertEquals(message.type, read.type)
        assertEquals(message.messageId, read.messageId)
        assertEquals(message.senderId, read.senderId)
        assertEquals(message.recipientId, read.recipientId)
        assertEquals(message.timestamp, read.timestamp)
    }

    @Test
    fun `P2PMessage Type enum has expected values`() {
        val types = P2PMessage.Type.entries
        assertEquals(5, types.size)
        assertTrue(types.contains(P2PMessage.Type.TEXT))
        assertTrue(types.contains(P2PMessage.Type.ACK))
        assertTrue(types.contains(P2PMessage.Type.PING))
        assertTrue(types.contains(P2PMessage.Type.PONG))
        assertTrue(types.contains(P2PMessage.Type.MEDIA))
    }
}
