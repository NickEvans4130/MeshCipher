package com.meshcipher.data.bluetooth.routing

data class RouteEntry(
    val destinationUserId: String,
    val nextHopDeviceId: String,
    val hopCount: Int,
    val lastUpdated: Long
)
