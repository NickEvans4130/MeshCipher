package com.meshcipher.data.recovery

import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.model.RecoveryShard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecoveryManager @Inject constructor(
    private val shamirSecretSharing: ShamirSecretSharing
) {

    suspend fun setupRecovery(guardians: List<Contact>): ByteArray = withContext(Dispatchers.IO) {
        require(guardians.size == 5) { "Need exactly 5 guardians" }

        val masterSeed = ByteArray(32)
        SecureRandom().nextBytes(masterSeed)

        val shards = shamirSecretSharing.split(
            secret = masterSeed,
            totalShards = 5,
            threshold = 3
        )

        val recoveryShards = shards.mapIndexed { index, shard ->
            RecoveryShard(
                shardIndex = index,
                encryptedShard = shard,
                guardianId = guardians[index].id,
                createdAt = System.currentTimeMillis()
            )
        }

        recoveryShards.forEach { shard ->
            Timber.d("Recovery shard %d assigned to guardian %s",
                shard.shardIndex, shard.guardianId)
        }

        masterSeed
    }

    suspend fun recoverFromShards(shards: List<ByteArray>): ByteArray = withContext(Dispatchers.IO) {
        require(shards.size >= 3) { "Need at least 3 shards to recover" }

        val masterSeed = shamirSecretSharing.combine(shards.take(3))

        Timber.d("Master seed recovered successfully")
        masterSeed
    }

    fun deriveRecoveryKey(masterSeed: ByteArray): ByteArray {
        return masterSeed
    }
}
