package com.meshcipher.domain.model

// GAP-07 / R-07: Serializable removed — WifiDirectMessage is serialised exclusively via
// WifiDirectMessageCodec (typed binary protocol). Java ObjectInputStream is no longer used.
sealed class WifiDirectMessage {
    abstract val senderId: String
    abstract val recipientId: String
    abstract val timestamp: Long

    data class TextMessage(
        override val senderId: String,
        override val recipientId: String,
        override val timestamp: Long,
        val encryptedContent: ByteArray
    ) : WifiDirectMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as TextMessage
            return senderId == other.senderId &&
                recipientId == other.recipientId &&
                timestamp == other.timestamp &&
                encryptedContent.contentEquals(other.encryptedContent)
        }

        override fun hashCode(): Int {
            var result = senderId.hashCode()
            result = 31 * result + recipientId.hashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + encryptedContent.contentHashCode()
            return result
        }
    }

    data class FileTransfer(
        override val senderId: String,
        override val recipientId: String,
        override val timestamp: Long,
        val fileId: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val totalChunks: Int
    ) : WifiDirectMessage()

    data class FileChunk(
        override val senderId: String,
        override val recipientId: String,
        override val timestamp: Long,
        val fileId: String,
        val chunkIndex: Int,
        val data: ByteArray
    ) : WifiDirectMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as FileChunk
            return senderId == other.senderId &&
                recipientId == other.recipientId &&
                timestamp == other.timestamp &&
                fileId == other.fileId &&
                chunkIndex == other.chunkIndex &&
                data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = senderId.hashCode()
            result = 31 * result + recipientId.hashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + fileId.hashCode()
            result = 31 * result + chunkIndex
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class Acknowledgment(
        override val senderId: String,
        override val recipientId: String,
        override val timestamp: Long,
        val messageId: String,
        val success: Boolean
    ) : WifiDirectMessage()

    companion object {
        const val CHUNK_SIZE = 64 * 1024 // 64KB chunks
    }
}
