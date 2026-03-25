package com.meshcipher.data.wifidirect

import com.meshcipher.domain.model.WifiDirectMessage
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Typed binary encoder/decoder for WiFi Direct messages (GAP-07 / R-07).
 *
 * Replaces Java ObjectInputStream/ObjectOutputStream to eliminate the RCE-via-gadget-chain
 * attack surface present in any code path that calls readObject() on untrusted data.
 *
 * Wire format:
 *   [1 byte  — message type ID, see WifiDirectMessageType]
 *   [4 bytes — payload length, big-endian signed int]
 *   [N bytes — payload bytes (typed, hand-coded serialisation — no reflection)]
 *
 * Security properties:
 * - An unknown type byte causes [IllegalArgumentException]; the payload is never read.
 * - A payload length > [MAX_PAYLOAD_BYTES] is rejected before any buffer allocation,
 *   preventing memory-exhaustion via large crafted length fields.
 * - A serialisation-magic header (0xACED) from a legacy Java stream is treated as an
 *   unknown type and rejected cleanly.
 */
object WifiDirectMessageCodec {

    /** Maximum allowed payload size. Messages claiming more are rejected. */
    const val MAX_PAYLOAD_BYTES = 10 * 1024 * 1024 // 10 MB

    // -------------------------------------------------------------------------
    // Encode
    // -------------------------------------------------------------------------

    fun encode(message: WifiDirectMessage, out: OutputStream) {
        val payload = encodePayload(message)
        val dos = DataOutputStream(out)
        dos.writeByte(typeId(message).toInt())
        dos.writeInt(payload.size)
        dos.write(payload)
        dos.flush()
    }

    private fun typeId(message: WifiDirectMessage): Byte = when (message) {
        is WifiDirectMessage.TextMessage -> WifiDirectMessageType.TEXT_MESSAGE
        is WifiDirectMessage.FileTransfer -> WifiDirectMessageType.FILE_TRANSFER
        is WifiDirectMessage.FileChunk -> WifiDirectMessageType.FILE_CHUNK
        is WifiDirectMessage.Acknowledgment -> WifiDirectMessageType.ACKNOWLEDGMENT
    }

    private fun encodePayload(message: WifiDirectMessage): ByteArray = when (message) {
        is WifiDirectMessage.TextMessage -> encodeTextMessage(message)
        is WifiDirectMessage.FileTransfer -> encodeFileTransfer(message)
        is WifiDirectMessage.FileChunk -> encodeFileChunk(message)
        is WifiDirectMessage.Acknowledgment -> encodeAcknowledgment(message)
    }

    // -------------------------------------------------------------------------
    // Decode
    // -------------------------------------------------------------------------

    /**
     * Reads exactly one message from [input].
     *
     * @throws IllegalArgumentException if the type byte is unknown or the declared
     *   payload length exceeds [MAX_PAYLOAD_BYTES].
     * @throws java.io.EOFException if the stream ends prematurely.
     */
    fun decode(input: InputStream): WifiDirectMessage {
        val dis = DataInputStream(input)
        val typeByte = dis.readByte()

        // GAP-07 / R-07: Reject unknown type bytes BEFORE reading the length field so an
        // attacker cannot use a crafted length to exhaust heap on an ultimately-rejected frame.
        if (typeByte != WifiDirectMessageType.TEXT_MESSAGE &&
            typeByte != WifiDirectMessageType.FILE_TRANSFER &&
            typeByte != WifiDirectMessageType.FILE_CHUNK &&
            typeByte != WifiDirectMessageType.ACKNOWLEDGMENT) {
            throw IllegalArgumentException(
                "Unknown WiFi Direct message type byte: 0x${typeByte.toInt().and(0xFF).toString(16)}"
            )
        }

        val length = dis.readInt()

        // GAP-07 / R-07: Reject oversized payloads before allocating any buffer.
        if (length < 0 || length > MAX_PAYLOAD_BYTES) {
            throw IllegalArgumentException(
                "WiFi Direct message payload length $length exceeds maximum $MAX_PAYLOAD_BYTES bytes"
            )
        }

        val payload = ByteArray(length)
        dis.readFully(payload)

        return when (typeByte) {
            WifiDirectMessageType.TEXT_MESSAGE -> decodeTextMessage(payload)
            WifiDirectMessageType.FILE_TRANSFER -> decodeFileTransfer(payload)
            WifiDirectMessageType.FILE_CHUNK -> decodeFileChunk(payload)
            else -> decodeAcknowledgment(payload) // ACKNOWLEDGMENT — only remaining valid type
        }
    }

    // -------------------------------------------------------------------------
    // Per-type encode helpers
    // Layout for each type is documented inline; DataOutputStream.writeUTF() uses
    // a 2-byte length prefix followed by modified UTF-8 — suitable for short strings.
    // -------------------------------------------------------------------------

    private fun encodeTextMessage(msg: WifiDirectMessage.TextMessage): ByteArray {
        val buf = ByteArrayOutputStream()
        val dos = DataOutputStream(buf)
        dos.writeUTF(msg.senderId)
        dos.writeUTF(msg.recipientId)
        dos.writeLong(msg.timestamp)
        dos.writeInt(msg.encryptedContent.size)
        dos.write(msg.encryptedContent)
        return buf.toByteArray()
    }

    private fun encodeFileTransfer(msg: WifiDirectMessage.FileTransfer): ByteArray {
        val buf = ByteArrayOutputStream()
        val dos = DataOutputStream(buf)
        dos.writeUTF(msg.senderId)
        dos.writeUTF(msg.recipientId)
        dos.writeLong(msg.timestamp)
        dos.writeUTF(msg.fileId)
        dos.writeUTF(msg.fileName)
        dos.writeLong(msg.fileSize)
        dos.writeUTF(msg.mimeType)
        dos.writeInt(msg.totalChunks)
        return buf.toByteArray()
    }

    private fun encodeFileChunk(msg: WifiDirectMessage.FileChunk): ByteArray {
        val buf = ByteArrayOutputStream()
        val dos = DataOutputStream(buf)
        dos.writeUTF(msg.senderId)
        dos.writeUTF(msg.recipientId)
        dos.writeLong(msg.timestamp)
        dos.writeUTF(msg.fileId)
        dos.writeInt(msg.chunkIndex)
        dos.writeInt(msg.data.size)
        dos.write(msg.data)
        return buf.toByteArray()
    }

    private fun encodeAcknowledgment(msg: WifiDirectMessage.Acknowledgment): ByteArray {
        val buf = ByteArrayOutputStream()
        val dos = DataOutputStream(buf)
        dos.writeUTF(msg.senderId)
        dos.writeUTF(msg.recipientId)
        dos.writeLong(msg.timestamp)
        dos.writeUTF(msg.messageId)
        dos.writeBoolean(msg.success)
        return buf.toByteArray()
    }

    // -------------------------------------------------------------------------
    // Per-type decode helpers
    // -------------------------------------------------------------------------

    private fun decodeTextMessage(payload: ByteArray): WifiDirectMessage.TextMessage {
        val dis = DataInputStream(payload.inputStream())
        val senderId = dis.readUTF()
        val recipientId = dis.readUTF()
        val timestamp = dis.readLong()
        val len = dis.readInt()
        if (len < 0 || len > payload.size) {
            throw IllegalArgumentException(
                "TextMessage encryptedContent length $len is invalid (payload size ${payload.size})"
            )
        }
        val content = ByteArray(len)
        dis.readFully(content)
        return WifiDirectMessage.TextMessage(senderId, recipientId, timestamp, content)
    }

    private fun decodeFileTransfer(payload: ByteArray): WifiDirectMessage.FileTransfer {
        val dis = DataInputStream(payload.inputStream())
        return WifiDirectMessage.FileTransfer(
            senderId = dis.readUTF(),
            recipientId = dis.readUTF(),
            timestamp = dis.readLong(),
            fileId = dis.readUTF(),
            fileName = dis.readUTF(),
            fileSize = dis.readLong(),
            mimeType = dis.readUTF(),
            totalChunks = dis.readInt()
        )
    }

    private fun decodeFileChunk(payload: ByteArray): WifiDirectMessage.FileChunk {
        val dis = DataInputStream(payload.inputStream())
        val senderId = dis.readUTF()
        val recipientId = dis.readUTF()
        val timestamp = dis.readLong()
        val fileId = dis.readUTF()
        val chunkIndex = dis.readInt()
        val len = dis.readInt()
        if (len < 0 || len > payload.size) {
            throw IllegalArgumentException(
                "FileChunk data length $len is invalid (payload size ${payload.size})"
            )
        }
        val data = ByteArray(len)
        dis.readFully(data)
        return WifiDirectMessage.FileChunk(senderId, recipientId, timestamp, fileId, chunkIndex, data)
    }

    private fun decodeAcknowledgment(payload: ByteArray): WifiDirectMessage.Acknowledgment {
        val dis = DataInputStream(payload.inputStream())
        return WifiDirectMessage.Acknowledgment(
            senderId = dis.readUTF(),
            recipientId = dis.readUTF(),
            timestamp = dis.readLong(),
            messageId = dis.readUTF(),
            success = dis.readBoolean()
        )
    }
}
