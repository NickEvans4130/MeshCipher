package com.meshcipher.data.bluetooth

import java.nio.ByteBuffer
import java.security.MessageDigest

data class AdvertisementData(
    val protocolVersion: Byte = PROTOCOL_VERSION,
    val messageType: MessageType,
    val deviceId: String,
    val userId: String,
    val timestamp: Long,
    val signature: ByteArray
) {
    enum class MessageType(val value: Byte) {
        BEACON(0x01),
        DATA(0x02),
        ROUTE_UPDATE(0x03);

        companion object {
            fun fromByte(value: Byte): MessageType? = entries.find { it.value == value }
        }
    }

    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(PACKET_SIZE)

        // Header (2 bytes)
        buffer.put(protocolVersion)
        buffer.put(messageType.value)

        // Device ID hash (32 bytes)
        val deviceIdHash = MessageDigest.getInstance("SHA-256")
            .digest(deviceId.toByteArray())
        buffer.put(deviceIdHash)

        // User ID hash (32 bytes)
        val userIdHash = MessageDigest.getInstance("SHA-256")
            .digest(userId.toByteArray())
        buffer.put(userIdHash)

        // Signature (64 bytes)
        val sig = if (signature.size >= SIGNATURE_SIZE) {
            signature.copyOf(SIGNATURE_SIZE)
        } else {
            signature + ByteArray(SIGNATURE_SIZE - signature.size)
        }
        buffer.put(sig)

        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AdvertisementData
        return deviceId == other.deviceId && userId == other.userId
    }

    override fun hashCode(): Int = 31 * deviceId.hashCode() + userId.hashCode()

    companion object {
        const val PROTOCOL_VERSION: Byte = 0x01
        const val PACKET_SIZE = 130 // 2 + 32 + 32 + 64
        private const val SIGNATURE_SIZE = 64

        fun fromBytes(bytes: ByteArray): AdvertisementData? {
            if (bytes.size < PACKET_SIZE) return null

            val buffer = ByteBuffer.wrap(bytes)

            val protocolVersion = buffer.get()
            val messageType = MessageType.fromByte(buffer.get()) ?: return null

            val deviceIdHash = ByteArray(32)
            buffer.get(deviceIdHash)

            val userIdHash = ByteArray(32)
            buffer.get(userIdHash)

            val signature = ByteArray(SIGNATURE_SIZE)
            buffer.get(signature)

            return AdvertisementData(
                protocolVersion = protocolVersion,
                messageType = messageType,
                deviceId = deviceIdHash.joinToString("") { "%02x".format(it) },
                userId = userIdHash.joinToString("") { "%02x".format(it) },
                timestamp = System.currentTimeMillis(),
                signature = signature
            )
        }
    }
}
