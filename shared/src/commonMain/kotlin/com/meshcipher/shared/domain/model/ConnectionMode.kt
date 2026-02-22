package com.meshcipher.shared.domain.model

enum class ConnectionMode {
    DIRECT,
    TOR_RELAY,
    P2P_ONLY,
    P2P_TOR
}

enum class TransportType {
    NONE,
    WIFI_DIRECT,
    DIRECT,
    TOR_RELAY,
    P2P_TOR,
    BLUETOOTH,
    QUEUED
}
