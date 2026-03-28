package com.meshcipher.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePaddingTest {

    // --- pad() output is always a multiple of BLOCK_SIZE ---

    @Test fun `pad output is multiple of block size for size 1`() =
        assertPaddedSizeIsMultiple(1)

    @Test fun `pad output is multiple of block size for size 255`() =
        assertPaddedSizeIsMultiple(255)

    @Test fun `pad output is multiple of block size for size 256`() =
        assertPaddedSizeIsMultiple(256)

    @Test fun `pad output is multiple of block size for size 257`() =
        assertPaddedSizeIsMultiple(257)

    @Test fun `pad output is multiple of block size for size 512`() =
        assertPaddedSizeIsMultiple(512)

    @Test fun `pad output is multiple of block size for size 65280`() =
        assertPaddedSizeIsMultiple(65_280)

    // --- round-trip pad + unpad produces original payload ---

    @Test fun `round-trip for size 1`() = assertRoundTrip(1)

    @Test fun `round-trip for size 255`() = assertRoundTrip(255)

    @Test fun `round-trip for size 256`() = assertRoundTrip(256)

    @Test fun `round-trip for size 257`() = assertRoundTrip(257)

    @Test fun `round-trip for size 512`() = assertRoundTrip(512)

    @Test fun `round-trip for size 65280`() = assertRoundTrip(65_280)

    // --- edge cases ---

    @Test fun `already-aligned payload receives full extra block`() {
        // A 255-byte payload: 1 sentinel + 255 pad = 256 bytes — aligned.
        // After pad(), size should be 256 (next multiple).
        val payload = ByteArray(255) { it.toByte() }
        val padded = MessagePadding.pad(payload)
        assertEquals(0, padded.size % MessagePadding.BLOCK_SIZE)
        // Extra block must have been added so payload is not zero-length after unpad.
        val recovered = MessagePadding.unpad(padded)
        assertArrayEquals(payload, recovered)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pad rejects payload exceeding max size`() {
        MessagePadding.pad(ByteArray(MessagePadding.MAX_PAYLOAD_BYTES + 1))
    }

    @Test fun `unpad on non-block-aligned data returns input unchanged (graceful fallback)`() {
        val unaligned = ByteArray(100) { 0x42 }
        val result = MessagePadding.unpad(unaligned)
        assertArrayEquals(unaligned, result)
    }

    @Test fun `unpad on empty array returns empty (graceful fallback)`() {
        val result = MessagePadding.unpad(ByteArray(0))
        assertArrayEquals(ByteArray(0), result)
    }

    // --- helpers ---

    private fun assertPaddedSizeIsMultiple(size: Int) {
        val payload = ByteArray(size) { (it % 127).toByte() }
        val padded = MessagePadding.pad(payload)
        assertEquals(
            "Padded size ${padded.size} is not a multiple of ${MessagePadding.BLOCK_SIZE}",
            0, padded.size % MessagePadding.BLOCK_SIZE
        )
    }

    private fun assertRoundTrip(size: Int) {
        val payload = ByteArray(size) { (it % 200).toByte() }
        val padded = MessagePadding.pad(payload)
        val recovered = MessagePadding.unpad(padded)
        assertArrayEquals("Round-trip failed for size $size", payload, recovered)
    }
}
