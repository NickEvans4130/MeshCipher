package com.meshcipher.shared.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.CoreCrypto.CC_SHA512
import platform.CoreCrypto.CC_SHA512_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
actual fun sha512(data: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA512_DIGEST_LENGTH.toInt())
    memScoped {
        val input = data.toUByteArray().toCValues()
        val output = allocArray<UByteVar>(CC_SHA512_DIGEST_LENGTH.toInt())
        CC_SHA512(input, data.size.toUInt(), output)
        for (i in 0 until CC_SHA512_DIGEST_LENGTH.toInt()) {
            digest[i] = output[i].toByte()
        }
    }
    return digest
}
