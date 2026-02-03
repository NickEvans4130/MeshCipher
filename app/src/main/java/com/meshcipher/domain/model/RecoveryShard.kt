package com.meshcipher.domain.model

data class RecoveryShard(
    val shardIndex: Int,
    val encryptedShard: ByteArray,
    val guardianId: String,
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RecoveryShard
        return shardIndex == other.shardIndex && guardianId == other.guardianId
    }

    override fun hashCode(): Int = 31 * shardIndex + guardianId.hashCode()
}
