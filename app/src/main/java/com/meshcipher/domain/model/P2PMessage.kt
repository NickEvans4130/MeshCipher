package com.meshcipher.domain.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

data class P2PMessage(
    @SerializedName("type") val type: Type,
    @SerializedName("messageId") val messageId: String,
    @SerializedName("senderId") val senderId: String,
    @SerializedName("recipientId") val recipientId: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("payload") val payload: String? = null
) {
    enum class Type {
        TEXT, ACK, PING, PONG
    }

    companion object {
        private val gson = Gson()

        fun fromJson(json: String): P2PMessage = gson.fromJson(json, P2PMessage::class.java)

        fun readFromStream(input: InputStream): P2PMessage {
            val dis = DataInputStream(input)
            val length = dis.readInt()
            if (length <= 0 || length > MAX_MESSAGE_SIZE) {
                throw IllegalArgumentException("Invalid message length: $length")
            }
            val bytes = ByteArray(length)
            dis.readFully(bytes)
            return fromJson(String(bytes, Charsets.UTF_8))
        }

        private const val MAX_MESSAGE_SIZE = 1024 * 1024 // 1MB
    }

    fun toJson(): String = gson.toJson(this)

    fun writeToStream(output: OutputStream) {
        val dos = DataOutputStream(output)
        val bytes = toJson().toByteArray(Charsets.UTF_8)
        dos.writeInt(bytes.size)
        dos.write(bytes)
        dos.flush()
    }
}
