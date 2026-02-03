package com.meshcipher.data.recovery

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

class ShamirSecretSharingTest {

    private lateinit var shamir: ShamirSecretSharing

    @Before
    fun setup() {
        shamir = ShamirSecretSharing()
    }

    @Test
    fun `split produces correct number of shards`() {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val shards = shamir.split(secret, totalShards = 5, threshold = 3)
        assertEquals(5, shards.size)
    }

    @Test
    fun `recovery with 3 of 5 shards succeeds`() {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val shards = shamir.split(secret, totalShards = 5, threshold = 3)

        val recovered = shamir.combine(listOf(shards[0], shards[2], shards[4]))
        assertArrayEquals(secret, recovered)
    }

    @Test
    fun `recovery with different 3 shards succeeds`() {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val shards = shamir.split(secret, totalShards = 5, threshold = 3)

        val recovered = shamir.combine(listOf(shards[1], shards[3], shards[4]))
        assertArrayEquals(secret, recovered)
    }

    @Test
    fun `recovery with all 5 shards succeeds`() {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val shards = shamir.split(secret, totalShards = 5, threshold = 3)

        val recovered = shamir.combine(shards)
        assertArrayEquals(secret, recovered)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `split with threshold greater than total fails`() {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        shamir.split(secret, totalShards = 3, threshold = 5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `split with threshold less than 2 fails`() {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        shamir.split(secret, totalShards = 3, threshold = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `combine with less than 2 shards fails`() {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val shards = shamir.split(secret, totalShards = 5, threshold = 3)
        shamir.combine(listOf(shards[0]))
    }

    @Test
    fun `recovery with corrupted shard produces wrong result`() {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val shards = shamir.split(secret, totalShards = 5, threshold = 3)

        // Corrupt one shard by modifying its y-value bytes
        val corruptedShard = shards[1].copyOf()
        corruptedShard[corruptedShard.size - 1] =
            (corruptedShard[corruptedShard.size - 1] + 1).toByte()

        val recovered = shamir.combine(listOf(shards[0], corruptedShard, shards[2]))
        assertFalse(secret.contentEquals(recovered))
    }

    @Test
    fun `different secrets produce different shards`() {
        val secret1 = ByteArray(32) { 1 }
        val secret2 = ByteArray(32) { 2 }

        val shards1 = shamir.split(secret1, totalShards = 5, threshold = 3)
        val shards2 = shamir.split(secret2, totalShards = 5, threshold = 3)

        assertFalse(shards1[0].contentEquals(shards2[0]))
    }

    @Test
    fun `known secret roundtrip`() {
        val secret = "meshcipher_recovery_seed_test!!"
            .toByteArray()
            .copyOf(32)

        val shards = shamir.split(secret, totalShards = 5, threshold = 3)
        val recovered = shamir.combine(listOf(shards[0], shards[1], shards[2]))

        assertArrayEquals(secret, recovered)
    }

    @Test
    fun `2-of-3 threshold works`() {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val shards = shamir.split(secret, totalShards = 3, threshold = 2)

        assertEquals(3, shards.size)
        assertArrayEquals(secret, shamir.combine(listOf(shards[0], shards[1])))
        assertArrayEquals(secret, shamir.combine(listOf(shards[0], shards[2])))
        assertArrayEquals(secret, shamir.combine(listOf(shards[1], shards[2])))
    }
}
