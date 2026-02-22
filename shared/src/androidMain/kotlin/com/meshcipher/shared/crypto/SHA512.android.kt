package com.meshcipher.shared.crypto

import java.security.MessageDigest

actual fun sha512(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-512").digest(data)
