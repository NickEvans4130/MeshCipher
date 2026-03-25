package com.meshcipher.data.bluetooth

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GAP-01 / R-01: Provides epoch-specific pseudonym UUIDs for BLE advertisement and scanning.
 *
 * Exposes the current epoch UUID (for advertising) and the previous epoch UUID (for scanning,
 * so that devices which haven't rotated yet can still be discovered during the transition window).
 */
@Singleton
class BleAdvertisementIdentityProvider @Inject constructor(
    private val epochKeyManager: BleEpochKeyManager
) {
    fun currentEpochUuid(): UUID {
        return epochKeyManager.epochUuid(epochKeyManager.epochNumber())
    }

    fun previousEpochUuid(): UUID {
        return epochKeyManager.epochUuid(epochKeyManager.epochNumber() - 1)
    }
}
