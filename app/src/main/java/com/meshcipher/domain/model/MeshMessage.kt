package com.meshcipher.domain.model

import java.nio.ByteBuffer

data class MeshMessage(
    val id: String,
    val originDeviceId: String,
    val originUserId: String,
    val destinationUserId: String,
    val encryptedPayload: ByteArray,
    val timestamp: Long,
    val ttl: Int = MAX_TTL,
    val hopCount: Int = 0,
    val path: List<String> = emptyList()
) {
    fun incrementHop(relayDeviceId: String): MeshMessage {
        return copy(
            hopCount = hopCount + 1,
            path = path + relayDeviceId
        )
    }

    fun shouldRelay(): Boolean = hopCount < ttl

    fun toBytes(): ByteArray {
        val idBytes = id.toByteArray()
        val originDeviceBytes = originDeviceId.toByteArray()
        val originUserBytes = originUserId.toByteArray()
        val destBytes = destinationUserId.toByteArray()
        val pathStr = path.joinToString(",")
        val pathBytes = pathStr.toByteArray()

        // Format: [idLen:2][id][originDeviceLen:2][originDevice][originUserLen:2][originUser]
        //         [destLen:2][dest][timestamp:8][ttl:4][hopCount:4]
        //         [pathLen:2][path][payloadLen:4][payload]
        val totalSize = 2 + idBytes.size +
                2 + originDeviceBytes.size +
                2 + originUserBytes.size +
                2 + destBytes.size +
                8 + 4 + 4 +
                2 + pathBytes.size +
                4 + encryptedPayload.size

        val buffer = ByteBuffer.allocate(totalSize)
        buffer.putShort(idBytes.size.toShort())
        buffer.put(idBytes)
        buffer.putShort(originDeviceBytes.size.toShort())
        buffer.put(originDeviceBytes)
        buffer.putShort(originUserBytes.size.toShort())
        buffer.put(originUserBytes)
        buffer.putShort(destBytes.size.toShort())
        buffer.put(destBytes)
        buffer.putLong(timestamp)
        buffer.putInt(ttl)
        buffer.putInt(hopCount)
        buffer.putShort(pathBytes.size.toShort())
        buffer.put(pathBytes)
        buffer.putInt(encryptedPayload.size)
        buffer.put(encryptedPayload)

        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MeshMessage
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    companion object {
        const val MAX_TTL = 5

        fun fromBytes(bytes: ByteArray): MeshMessage? {
            return try {
                val buffer = ByteBuffer.wrap(bytes)

                val idLen = buffer.short.toInt()
                val idBytes = ByteArray(idLen)
                buffer.get(idBytes)

                val originDeviceLen = buffer.short.toInt()
                val originDeviceBytes = ByteArray(originDeviceLen)
                buffer.get(originDeviceBytes)

                val originUserLen = buffer.short.toInt()
                val originUserBytes = ByteArray(originUserLen)
                buffer.get(originUserBytes)

                val destLen = buffer.short.toInt()
                val destBytes = ByteArray(destLen)
                buffer.get(destBytes)

                val timestamp = buffer.long
                val ttl = buffer.int
                val hopCount = buffer.int

                val pathLen = buffer.short.toInt()
                val pathBytes = ByteArray(pathLen)
                buffer.get(pathBytes)
                val pathStr = String(pathBytes)
                val path = if (pathStr.isEmpty()) emptyList() else pathStr.split(",")

                val payloadLen = buffer.int
                val payload = ByteArray(payloadLen)
                buffer.get(payload)

                MeshMessage(
                    id = String(idBytes),
                    originDeviceId = String(originDeviceBytes),
                    originUserId = String(originUserBytes),
                    destinationUserId = String(destBytes),
                    encryptedPayload = payload,
                    timestamp = timestamp,
                    ttl = ttl,
                    hopCount = hopCount,
                    path = path
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
