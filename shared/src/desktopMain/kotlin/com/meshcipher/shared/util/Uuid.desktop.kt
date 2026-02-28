package com.meshcipher.shared.util

actual fun generateUUID(): String = java.util.UUID.randomUUID().toString()
