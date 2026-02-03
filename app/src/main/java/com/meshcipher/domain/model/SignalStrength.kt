package com.meshcipher.domain.model

enum class SignalStrength {
    EXCELLENT,
    GOOD,
    FAIR,
    WEAK;

    companion object {
        fun fromRssi(rssi: Int): SignalStrength = when {
            rssi >= -50 -> EXCELLENT
            rssi >= -70 -> GOOD
            rssi >= -80 -> FAIR
            else -> WEAK
        }
    }
}
