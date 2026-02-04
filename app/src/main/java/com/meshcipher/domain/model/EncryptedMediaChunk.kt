package com.meshcipher.domain.model

/**
 * Represents an encrypted chunk of media data with its encryption parameters.
 * Each chunk is encrypted with AES-256-GCM using a unique key.
 */
data class EncryptedMediaChunk(
    val index: Int,
    val encryptedData: ByteArray,
    val nonce: ByteArray,
    val chunkKey: ByteArray,
    val originalHash: String,
    val cid: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedMediaChunk
        return index == other.index && originalHash == other.originalHash
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + originalHash.hashCode()
        return result
    }
}
