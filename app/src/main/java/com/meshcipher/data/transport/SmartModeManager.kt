package com.meshcipher.data.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the transport that was last used to deliver a message and exposes it as a
 * StateFlow so the chat UI can show a live indicator (e.g. "WiFi Direct", "Offline").
 *
 * TransportManager calls [reportTransportUsed] after every successful or queued send.
 * This class intentionally has no dependency on TransportManager to avoid a cycle.
 */
@Singleton
class SmartModeManager @Inject constructor() {

    enum class ActiveTransport {
        NONE,
        WIFI_DIRECT,
        INTERNET,
        TOR_RELAY,
        P2P_TOR,
        BLUETOOTH,
        QUEUED
    }

    private val _activeTransport = MutableStateFlow(ActiveTransport.NONE)
    val activeTransport: StateFlow<ActiveTransport> = _activeTransport

    private val _relayOffline = MutableStateFlow(false)
    val relayOffline: StateFlow<Boolean> = _relayOffline

    fun reportRelayOffline(offline: Boolean) {
        if (_relayOffline.value != offline) {
            Timber.d("Relay status changed: %s", if (offline) "OFFLINE" else "ONLINE")
            _relayOffline.value = offline
        }
    }

    fun reportTransportUsed(transport: ActiveTransport) {
        if (_activeTransport.value != transport) {
            Timber.d("Smart Mode: active transport -> %s", transport.name)
            _activeTransport.value = transport
        }
    }

    fun getDisplayLabel(transport: ActiveTransport): String = when (transport) {
        ActiveTransport.WIFI_DIRECT -> "WiFi Direct"
        ActiveTransport.INTERNET    -> "Connected"
        ActiveTransport.TOR_RELAY   -> "TOR Relay"
        ActiveTransport.P2P_TOR     -> "P2P TOR"
        ActiveTransport.BLUETOOTH   -> "Bluetooth"
        ActiveTransport.QUEUED      -> "Offline"
        ActiveTransport.NONE        -> "Connecting..."
    }
}
