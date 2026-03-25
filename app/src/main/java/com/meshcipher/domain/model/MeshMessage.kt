package com.meshcipher.domain.model

import java.nio.ByteBuffer

// GAP-02 / R-02: `path` field removed — hop-by-hop device IDs are not transmitted in the
// wire format and are not stored on the model.  Loop prevention uses the `seenMessages` LRU
// set in MeshRouter (keyed on message ID) rather than path inspection.
data class MeshMessage(
    val id: String,
    val originDeviceId: String,
    val originUserId: String,
    val destinationUserId: String,
    val encryptedPayload: ByteArray,
    val timestamp: Long,
    val ttl: Int = MAX_TTL,
    val hopCount: Int = 0
) {
    fun incrementHop(): MeshMessage = copy(hopCount = hopCount + 1)

    fun shouldRelay(): Boolean = hopCount < ttl

    fun toBytes(): ByteArray {
        val idBytes = id.toByteArray()
        val originDeviceBytes = originDeviceId.toByteArray()
        val originUserBytes = originUserId.toByteArray()
        val destBytes = destinationUserId.toByteArray()

        // Format: [idLen:2][id][originDeviceLen:2][originDevice][originUserLen:2][originUser]
        //         [destLen:2][dest][timestamp:8][ttl:4][hopCount:4][payloadLen:4][payload]
        // GAP-02 / R-02: path field omitted from wire format.
        val totalSize = 2 + idBytes.size +
                2 + originDeviceBytes.size +
                2 + originUserBytes.size +
                2 + destBytes.size +
                8 + 4 + 4 +
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
                    hopCount = hopCount
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
