package com.meshcipher.data.crypto

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt-injectable singleton that delegates to the shared KMM SafetyNumberGenerator.
 */
@Singleton
class SafetyNumberGenerator @Inject constructor() :
    com.meshcipher.shared.crypto.SafetyNumberGenerator()
