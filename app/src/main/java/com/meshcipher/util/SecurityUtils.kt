package com.meshcipher.util

import java.security.MessageDigest

object SecurityUtils {

    fun wipeByteArray(array: ByteArray) {
        array.fill(0)
    }

    fun computeMessageHash(
        senderId: String,
        recipientId: String,
        content: ByteArray,
        timestamp: Long,
        sequenceNumber: Long
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(senderId.toByteArray())
        digest.update(recipientId.toByteArray())
        digest.update(content)
        digest.update(longToBytes(timestamp))
        digest.update(longToBytes(sequenceNumber))
        return digest.digest()
    }

    private fun longToBytes(value: Long): ByteArray {
        return ByteArray(8) { i ->
            (value shr (56 - 8 * i) and 0xFF).toByte()
        }
    }
}
