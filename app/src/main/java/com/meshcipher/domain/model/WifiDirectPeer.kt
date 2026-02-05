package com.meshcipher.domain.model

import android.net.wifi.p2p.WifiP2pDevice

data class WifiDirectPeer(
    val deviceName: String,
    val deviceAddress: String,
    val status: ConnectionStatus,
    val isGroupOwner: Boolean = false
) {
    enum class ConnectionStatus {
        AVAILABLE,
        INVITED,
        CONNECTED,
        FAILED,
        UNAVAILABLE
    }

    companion object {
        fun fromWifiP2pDevice(device: WifiP2pDevice): WifiDirectPeer {
            return WifiDirectPeer(
                deviceName = device.deviceName ?: "Unknown Device",
                deviceAddress = device.deviceAddress ?: "",
                status = when (device.status) {
                    WifiP2pDevice.AVAILABLE -> ConnectionStatus.AVAILABLE
                    WifiP2pDevice.INVITED -> ConnectionStatus.INVITED
                    WifiP2pDevice.CONNECTED -> ConnectionStatus.CONNECTED
                    WifiP2pDevice.FAILED -> ConnectionStatus.FAILED
                    else -> ConnectionStatus.UNAVAILABLE
                },
                isGroupOwner = device.isGroupOwner
            )
        }
    }
}
