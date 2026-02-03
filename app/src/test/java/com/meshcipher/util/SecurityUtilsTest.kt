package com.meshcipher.util

import org.junit.Assert.*
import org.junit.Test

class SecurityUtilsTest {

    @Test
    fun `wipeByteArray clears all bytes`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        SecurityUtils.wipeByteArray(data)
        assertTrue(data.all { it == 0.toByte() })
    }

    @Test
    fun `computeMessageHash produces consistent results`() {
        val hash1 = SecurityUtils.computeMessageHash(
            senderId = "alice",
            recipientId = "bob",
            content = "hello".toByteArray(),
            timestamp = 1000L,
            sequenceNumber = 1L
        )

        val hash2 = SecurityUtils.computeMessageHash(
            senderId = "alice",
            recipientId = "bob",
            content = "hello".toByteArray(),
            timestamp = 1000L,
            sequenceNumber = 1L
        )

        assertArrayEquals(hash1, hash2)
    }

    @Test
    fun `computeMessageHash differs with different content`() {
        val hash1 = SecurityUtils.computeMessageHash(
            senderId = "alice",
            recipientId = "bob",
            content = "hello".toByteArray(),
            timestamp = 1000L,
            sequenceNumber = 1L
        )

        val hash2 = SecurityUtils.computeMessageHash(
            senderId = "alice",
            recipientId = "bob",
            content = "world".toByteArray(),
            timestamp = 1000L,
            sequenceNumber = 1L
        )

        assertFalse(hash1.contentEquals(hash2))
    }

    @Test
    fun `computeMessageHash differs with different sequence`() {
        val hash1 = SecurityUtils.computeMessageHash(
            senderId = "alice",
            recipientId = "bob",
            content = "hello".toByteArray(),
            timestamp = 1000L,
            sequenceNumber = 1L
        )

        val hash2 = SecurityUtils.computeMessageHash(
            senderId = "alice",
            recipientId = "bob",
            content = "hello".toByteArray(),
            timestamp = 1000L,
            sequenceNumber = 2L
        )

        assertFalse(hash1.contentEquals(hash2))
    }

    @Test
    fun `computeMessageHash is 32 bytes SHA-256`() {
        val hash = SecurityUtils.computeMessageHash(
            senderId = "alice",
            recipientId = "bob",
            content = "hello".toByteArray(),
            timestamp = 1000L,
            sequenceNumber = 1L
        )

        assertEquals(32, hash.size)
    }
}
