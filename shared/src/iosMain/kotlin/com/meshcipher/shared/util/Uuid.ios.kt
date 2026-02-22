package com.meshcipher.shared.util

import platform.Foundation.NSUUID

actual fun generateUUID(): String = NSUUID().UUIDString()
