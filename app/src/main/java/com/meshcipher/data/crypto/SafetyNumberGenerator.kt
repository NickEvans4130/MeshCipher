package com.meshcipher.data.crypto

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafetyNumberGenerator @Inject constructor() {

    companion object {
        private const val ITERATIONS = 5200
        private val VERSION = byteArrayOf(0)
    }

    /**
     * Generate a 120-digit safety number between two users.
     * Based on Signal Protocol safety number specification.
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

        val firstFingerprint = computeFingerprint(firstUserId.toByteArray(Charsets.UTF_8), firstKey)
        val secondFingerprint = computeFingerprint(secondUserId.toByteArray(Charsets.UTF_8), secondKey)

        return formatFingerprint(firstFingerprint) + formatFingerprint(secondFingerprint)
    }

    /**
     * Compute a single-user fingerprint via iterated SHA-512.
     * Input: VERSION || publicKey || userId, hashed 5200 times.
     */
    private fun computeFingerprint(userId: ByteArray, publicKey: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        var hash = digest.digest(VERSION + publicKey + userId)
        repeat(ITERATIONS - 1) {
            hash = digest.digest(hash)
        }
        return hash
    }

    /**
     * Convert the first 30 bytes of a fingerprint to 60 decimal digits.
     * Each byte maps to a 2-digit value (0-99).
     */
    private fun formatFingerprint(hash: ByteArray): String {
        val sb = StringBuilder(60)
        for (i in 0 until 30) {
            sb.append(String.format("%02d", hash[i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    /**
     * Format a 120-digit safety number as 24 groups of 5 for human readability.
     * Example: "12345 67890 12345 ..."
     */
    fun formatForDisplay(safetyNumber: String): String {
        return safetyNumber.chunked(5).joinToString(" ")
    }

    /**
     * Encode safety number as a scannable QR code URL.
     */
    fun generateQRContent(safetyNumber: String): String {
        return "meshcipher://verify/$safetyNumber"
    }

    /**
     * Parse a safety number from a scanned QR code URL.
     * Returns null if the URL does not match expected format.
     */
    fun parseSafetyNumberFromQR(qrContent: String): String? {
        val prefix = "meshcipher://verify/"
        return if (qrContent.startsWith(prefix)) qrContent.removePrefix(prefix) else null
    }
}
