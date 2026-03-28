package com.meshcipher.domain.model

import com.meshcipher.data.transport.OnionRoutingHeader
import com.meshcipher.data.transport.RoutingMode
import java.nio.ByteBuffer

// GAP-02 / R-02: `path` field removed — hop-by-hop device IDs are not transmitted in the
// wire format and are not stored on the model.  Loop prevention uses the `seenMessages` LRU
// set in MeshRouter (keyed on message ID) rather than path inspection.
// MD-04: `routingMode` and `onionHeader` added for source-routing onion support.
data class MeshMessage(
    val id: String,
    val originDeviceId: String,
    val originUserId: String,
    val destinationUserId: String,
    val encryptedPayload: ByteArray,
    val timestamp: Long,
    val ttl: Int = MAX_TTL,
    val hopCount: Int = 0,
    // MD-04: routing mode and onion header. Non-null only when routingMode == ONION.
    val routingMode: RoutingMode = RoutingMode.PLAINTEXT,
    val onionHeader: OnionRoutingHeader? = null
) {
    fun incrementHop(): MeshMessage = copy(hopCount = hopCount + 1)

    fun shouldRelay(): Boolean = hopCount < ttl

    fun toBytes(): ByteArray {
        val idBytes = id.toByteArray()
        val originDeviceBytes = originDeviceId.toByteArray()
        val originUserBytes = originUserId.toByteArray()
        val destBytes = destinationUserId.toByteArray()

        // MD-04: serialise onion header when routingMode == ONION.
        val onionBytes: ByteArray = if (routingMode == RoutingMode.ONION && onionHeader != null) {
            com.meshcipher.data.routing.OnionBuilder.serialiseOnion(onionHeader)
        } else {
            ByteArray(0)
        }

        // Format: [idLen:2][id][originDeviceLen:2][originDevice][originUserLen:2][originUser]
        //         [destLen:2][dest][timestamp:8][ttl:4][hopCount:4][payloadLen:4][payload]
        //         [routingMode:1][onionLen:4][onion]
        // GAP-02 / R-02: path field omitted from wire format.
        val totalSize = 2 + idBytes.size +
                2 + originDeviceBytes.size +
                2 + originUserBytes.size +
                2 + destBytes.size +
                8 + 4 + 4 +
                4 + encryptedPayload.size +
                1 + 4 + onionBytes.size

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
        buffer.put(if (routingMode == RoutingMode.ONION) 1.toByte() else 0.toByte())
        buffer.putInt(onionBytes.size)
        if (onionBytes.isNotEmpty()) buffer.put(onionBytes)

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

                // MD-04: read routing mode and optional onion header (older wire format
                // will have no remaining bytes — default to PLAINTEXT for backwards compat).
                var routingMode = RoutingMode.PLAINTEXT
                var onionHeader: OnionRoutingHeader? = null
                if (buffer.hasRemaining()) {
                    routingMode = if (buffer.get() == 1.toByte()) RoutingMode.ONION else RoutingMode.PLAINTEXT
                    val onionLen = if (buffer.hasRemaining()) buffer.int else 0
                    if (onionLen > 0 && routingMode == RoutingMode.ONION) {
                        val onionBytes = ByteArray(onionLen)
                        buffer.get(onionBytes)
                        onionHeader = com.meshcipher.data.routing.OnionPeeler.deserialiseOnionPublic(onionBytes)
                    }
                }

                MeshMessage(
                    id = String(idBytes),
                    originDeviceId = String(originDeviceBytes),
                    originUserId = String(originUserBytes),
                    destinationUserId = String(destBytes),
                    encryptedPayload = payload,
                    timestamp = timestamp,
                    ttl = ttl,
                    hopCount = hopCount,
                    routingMode = routingMode,
                    onionHeader = onionHeader
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
