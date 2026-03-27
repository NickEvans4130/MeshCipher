package com.meshcipher.util

/**
 * MD-02: Fixed-block padding for message payloads.
 *
 * All padded messages are multiples of BLOCK_SIZE bytes, preventing traffic analysis
 * based on ciphertext length (short ACK vs long message vs file).
 *
 * Wire format after padding:
 *   byte[0]           — number of padding bytes appended (0..255); a full block of
 *                       padding is added when the payload is already aligned, so this
 *                       value is never ambiguous.
 *   byte[1..padLen]   — padding bytes (0x00)
 *   byte[padLen+1..]  — original payload
 *
 * Padding is prepended (not appended) so the pad-length sentinel is at a fixed offset.
 *
 * Maximum input size: MAX_PAYLOAD_BYTES (65 281 bytes).  Payloads larger than this
 * would require more than 255 padding bytes to reach the next 256-byte boundary, which
 * cannot be encoded in the single sentinel byte.
 */
object MessagePadding {

    const val BLOCK_SIZE = 256
    // A single byte encodes pad length (0..255), so max payload before padding is
    // (256 * 256) - 1 - 255 = 65_280.  We use 65_281 as the exclusive upper bound
    // (payload of exactly 65_281 bytes needs 255 pad bytes + 1 sentinel = 65_537 > max
    // block, so we cap at 65_280 inclusive).
    const val MAX_PAYLOAD_BYTES = 65_280

    /**
     * Pad [payload] to the next [BLOCK_SIZE]-byte boundary.
     *
     * If the payload is already a multiple of [BLOCK_SIZE] bytes, a full extra block is
     * added so that the output is never unpadded.
     *
     * @throws IllegalArgumentException if [payload] exceeds [MAX_PAYLOAD_BYTES].
     */
    fun pad(payload: ByteArray): ByteArray {
        if (payload.size > MAX_PAYLOAD_BYTES) {
            throw IllegalArgumentException(
                "Payload size ${payload.size} exceeds maximum padded message size $MAX_PAYLOAD_BYTES"
            )
        }

        // How many zero bytes to insert after the sentinel so the total length is a
        // multiple of BLOCK_SIZE:
        //   total = 1 (sentinel) + padCount + payload.size  must be 0 mod BLOCK_SIZE
        val rem = (1 + payload.size) % BLOCK_SIZE
        val padCount = if (rem == 0) BLOCK_SIZE else BLOCK_SIZE - rem

        // padCount is always in [1, BLOCK_SIZE]; since BLOCK_SIZE == 256 the value fits
        // in a byte (we store it minus one... actually just store as int casted to byte;
        // 256 wraps but we cap payload so rem==0 gives padCount==256 which we store as 0
        // — that would be ambiguous.  Instead use the invariant: if payload is aligned,
        // always add a full block.  padCount is in [1,256]; encode as (padCount - 1)
        // fitting in [0,255], decode as (sentinel.toInt() and 0xFF) + 1.
        val encodedPadLen = (padCount - 1).toByte()

        val result = ByteArray(1 + padCount + payload.size)
        result[0] = encodedPadLen
        // bytes [1..padCount] remain 0x00 (default)
        System.arraycopy(payload, 0, result, 1 + padCount, payload.size)
        return result
    }

    /**
     * Strip padding added by [pad].
     *
     * If [payload] does not appear to be padded (too short or not a multiple of
     * [BLOCK_SIZE]), the original array is returned unchanged as a graceful fallback for
     * receivers that may interact with unpadded senders.
     *
     * @throws IllegalArgumentException if the encoded pad length is inconsistent with
     *   the buffer size.
     */
    fun unpad(payload: ByteArray): ByteArray {
        // Graceful fallback: if not a multiple of BLOCK_SIZE, assume no padding.
        if (payload.size < BLOCK_SIZE || payload.size % BLOCK_SIZE != 0) {
            return payload
        }

        val padCount = (payload[0].toInt() and 0xFF) + 1
        val payloadStart = 1 + padCount
        if (payloadStart > payload.size) {
            throw IllegalArgumentException(
                "Corrupt padded message: pad length $padCount exceeds buffer size ${payload.size}"
            )
        }

        return payload.copyOfRange(payloadStart, payload.size)
    }
}
