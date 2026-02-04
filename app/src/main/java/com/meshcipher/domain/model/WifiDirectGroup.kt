package com.meshcipher.domain.model

import android.net.wifi.p2p.WifiP2pGroup

data class WifiDirectGroup(
    val networkName: String,
    val passphrase: String?,
    val isGroupOwner: Boolean,
    val groupOwnerAddress: String?,
    val clients: List<WifiDirectPeer>
) {
    companion object {
        fun fromWifiP2pGroup(group: WifiP2pGroup): WifiDirectGroup {
            return WifiDirectGroup(
                networkName = group.networkName ?: "",
                passphrase = group.passphrase,
                isGroupOwner = group.isGroupOwner,
                groupOwnerAddress = group.owner?.deviceAddress,
                clients = group.clientList?.map { WifiDirectPeer.fromWifiP2pDevice(it) } ?: emptyList()
            )
        }
    }
}
