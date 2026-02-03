package com.meshcipher.domain.model

data class MeshPeer(
    val deviceId: String,
    val userId: String,
    val displayName: String?,
    val bluetoothAddress: String,
    val rssi: Int,
    val lastSeen: Long,
    val isContact: Boolean,
    val hopCount: Int = 1,
    val reachableVia: String? = null
) {
    val isInRange: Boolean
        get() = hopCount == 1 && (System.currentTimeMillis() - lastSeen) < PEER_TIMEOUT_MS

    val signalStrength: SignalStrength
        get() = SignalStrength.fromRssi(rssi)

    companion object {
        const val PEER_TIMEOUT_MS = 30_000L
    }
}
