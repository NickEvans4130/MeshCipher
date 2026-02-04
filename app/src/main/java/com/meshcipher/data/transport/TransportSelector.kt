package com.meshcipher.data.transport

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransportSelector @Inject constructor(
    private val transportManager: TransportManager
) {
    enum class TransportType {
        INTERNET,
        WIFI_DIRECT,
        BLUETOOTH_MESH
    }

    data class TransportRecommendation(
        val primary: TransportType,
        val fallbacks: List<TransportType>,
        val reason: String
    )

    fun selectBestTransport(
        messageSizeBytes: Long,
        recipientId: String
    ): TransportRecommendation {
        val internetAvailable = try {
            transportManager.getActiveTransport() != null
        } catch (e: Exception) {
            false
        }
        val wifiDirectAvailable = transportManager.isWifiDirectAvailable()
        val bluetoothAvailable = transportManager.isMeshAvailable()

        Timber.d("Transport availability - Internet: $internetAvailable, WiFi Direct: $wifiDirectAvailable, Bluetooth: $bluetoothAvailable")
        Timber.d("Message size: $messageSizeBytes bytes")

        return when {
            // Very large files (>50MB): WiFi Direct only (too large for Bluetooth)
            messageSizeBytes > LARGE_FILE_THRESHOLD -> {
                when {
                    wifiDirectAvailable -> TransportRecommendation(
                        primary = TransportType.WIFI_DIRECT,
                        fallbacks = if (internetAvailable) listOf(TransportType.INTERNET) else emptyList(),
                        reason = "Large file requires WiFi Direct for efficient transfer"
                    )
                    internetAvailable -> TransportRecommendation(
                        primary = TransportType.INTERNET,
                        fallbacks = emptyList(),
                        reason = "Large file, WiFi Direct unavailable, using Internet"
                    )
                    else -> TransportRecommendation(
                        primary = TransportType.WIFI_DIRECT,
                        fallbacks = listOf(TransportType.BLUETOOTH_MESH),
                        reason = "Large file, no optimal transport available"
                    )
                }
            }

            // Medium files (1MB-50MB): Prefer WiFi Direct, Internet fallback
            messageSizeBytes > MEDIUM_FILE_THRESHOLD -> {
                when {
                    wifiDirectAvailable -> TransportRecommendation(
                        primary = TransportType.WIFI_DIRECT,
                        fallbacks = listOfNotNull(
                            if (internetAvailable) TransportType.INTERNET else null,
                            if (bluetoothAvailable) TransportType.BLUETOOTH_MESH else null
                        ),
                        reason = "Medium file, WiFi Direct preferred for speed"
                    )
                    internetAvailable -> TransportRecommendation(
                        primary = TransportType.INTERNET,
                        fallbacks = listOfNotNull(
                            if (bluetoothAvailable) TransportType.BLUETOOTH_MESH else null
                        ),
                        reason = "Medium file via Internet"
                    )
                    bluetoothAvailable -> TransportRecommendation(
                        primary = TransportType.BLUETOOTH_MESH,
                        fallbacks = emptyList(),
                        reason = "Medium file via Bluetooth (slower)"
                    )
                    else -> TransportRecommendation(
                        primary = TransportType.INTERNET,
                        fallbacks = emptyList(),
                        reason = "No transport available"
                    )
                }
            }

            // Small messages (<1MB): Use whatever is available, prefer Internet
            else -> {
                when {
                    internetAvailable -> TransportRecommendation(
                        primary = TransportType.INTERNET,
                        fallbacks = listOfNotNull(
                            if (wifiDirectAvailable) TransportType.WIFI_DIRECT else null,
                            if (bluetoothAvailable) TransportType.BLUETOOTH_MESH else null
                        ),
                        reason = "Small message via Internet (fastest)"
                    )
                    wifiDirectAvailable -> TransportRecommendation(
                        primary = TransportType.WIFI_DIRECT,
                        fallbacks = listOfNotNull(
                            if (bluetoothAvailable) TransportType.BLUETOOTH_MESH else null
                        ),
                        reason = "Small message via WiFi Direct (offline)"
                    )
                    bluetoothAvailable -> TransportRecommendation(
                        primary = TransportType.BLUETOOTH_MESH,
                        fallbacks = emptyList(),
                        reason = "Small message via Bluetooth mesh"
                    )
                    else -> TransportRecommendation(
                        primary = TransportType.INTERNET,
                        fallbacks = emptyList(),
                        reason = "No transport available"
                    )
                }
            }
        }
    }

    fun getAvailableTransports(): List<TransportType> {
        val available = mutableListOf<TransportType>()

        try {
            if (transportManager.getActiveTransport() != null) {
                available.add(TransportType.INTERNET)
            }
        } catch (e: Exception) {
            // Internet not available
        }

        if (transportManager.isWifiDirectAvailable()) {
            available.add(TransportType.WIFI_DIRECT)
        }

        if (transportManager.isMeshAvailable()) {
            available.add(TransportType.BLUETOOTH_MESH)
        }

        return available
    }

    companion object {
        const val LARGE_FILE_THRESHOLD = 50L * 1024 * 1024  // 50MB
        const val MEDIUM_FILE_THRESHOLD = 1L * 1024 * 1024  // 1MB
    }
}
