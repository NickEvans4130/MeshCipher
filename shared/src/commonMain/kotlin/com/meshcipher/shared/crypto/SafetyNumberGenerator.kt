package com.meshcipher.shared.crypto

open class SafetyNumberGenerator {

    companion object {
        private const val ITERATIONS = 5200
        private val VERSION = byteArrayOf(0)
    }

    /**
     * Generate a 120-digit safety number between two users.
     * Ordering is deterministic (lexicographic by userId) so both parties
     * always compute the same number independently.
     */
    fun generateSafetyNumber(
        localUserId: String,
        localPublicKey: ByteArray,
        remoteUserId: String,
        remotePublicKey: ByteArray
    ): String {
        val firstUserId: String
        val firstKey: ByteArray
        val secondUserId: String
        val secondKey: ByteArray
        if (localUserId < remoteUserId) {
            firstUserId = localUserId; firstKey = localPublicKey
            secondUserId = remoteUserId; secondKey = remotePublicKey
        } else {
            firstUserId = remoteUserId; firstKey = remotePublicKey
            secondUserId = localUserId; secondKey = localPublicKey
        }

        val firstFingerprint = computeFingerprint(firstUserId.encodeToByteArray(), firstKey)
        val secondFingerprint = computeFingerprint(secondUserId.encodeToByteArray(), secondKey)

        return formatFingerprint(firstFingerprint) + formatFingerprint(secondFingerprint)
    }

    /**
     * Compute a single-user fingerprint via iterated SHA-512.
     * Input: VERSION || publicKey || userId, hashed 5200 times.
     */
    private fun computeFingerprint(userId: ByteArray, publicKey: ByteArray): ByteArray {
        var hash = sha512(VERSION + publicKey + userId)
        repeat(ITERATIONS - 1) {
            hash = sha512(hash)
        }
        return hash
    }

    /**
     * Convert the first 30 bytes of a fingerprint to 60 decimal digits.
     * Each byte maps to a 2-digit value (0-99) via modulo 100.
     */
    private fun formatFingerprint(hash: ByteArray): String {
        val sb = StringBuilder(60)
        for (i in 0 until 30) {
            val value = (hash[i].toInt() and 0xFF) % 100
            sb.append(value.toString().padStart(2, '0'))
        }
        return sb.toString()
    }

    /**
     * Format a 120-digit safety number as 24 groups of 5 for human readability.
     */
    fun formatForDisplay(safetyNumber: String): String =
        safetyNumber.chunked(5).joinToString(" ")

    /**
     * Encode a safety number as a scannable QR code URL.
     */
    fun generateQRContent(safetyNumber: String): String =
        "meshcipher://verify/$safetyNumber"

    /**
     * Parse a safety number from a scanned QR code URL.
     */
    fun parseSafetyNumberFromQR(qrContent: String): String? {
        val prefix = "meshcipher://verify/"
        return if (qrContent.startsWith(prefix)) qrContent.removePrefix(prefix) else null
    }
}

/** Platform-specific SHA-512 implementation. */
expect fun sha512(data: ByteArray): ByteArray
