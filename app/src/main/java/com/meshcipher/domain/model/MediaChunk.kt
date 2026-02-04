package com.meshcipher.domain.model

import java.security.MessageDigest

/**
 * Represents a chunk of media data before encryption.
 * Media files are split into 64KB chunks for memory efficiency.
 */
data class MediaChunk(
    val index: Int,
    val data: ByteArray,
    val hash: String
) {
    companion object {
        const val CHUNK_SIZE = 64 * 1024 // 64KB chunks

        /**
         * Creates a chunk from raw data, computing its SHA-256 hash.
         */
        fun create(index: Int, data: ByteArray): MediaChunk {
            val hash = computeHash(data)
            return MediaChunk(index, data, hash)
        }

        /**
         * Computes SHA-256 hash of data as hex string.
         */
        fun computeHash(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(data)
            return hashBytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Splits a file into chunks.
         */
        fun splitIntoChunks(data: ByteArray): List<MediaChunk> {
            val chunks = mutableListOf<MediaChunk>()
            var offset = 0
            var index = 0

            while (offset < data.size) {
                val end = minOf(offset + CHUNK_SIZE, data.size)
                val chunkData = data.copyOfRange(offset, end)
                chunks.add(create(index, chunkData))
                offset = end
                index++
            }

            return chunks
        }
    }

    /**
     * Verifies the chunk data matches its hash.
     */
    fun verifyIntegrity(): Boolean {
        return computeHash(data) == hash
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MediaChunk
        return index == other.index && hash == other.hash
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + hash.hashCode()
        return result
    }
}
