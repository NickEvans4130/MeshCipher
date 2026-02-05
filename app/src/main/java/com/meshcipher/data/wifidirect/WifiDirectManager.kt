package com.meshcipher.data.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import com.meshcipher.domain.model.WifiDirectGroup
import com.meshcipher.domain.model.WifiDirectPeer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiDirectManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val wifiP2pManager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val _isWifiP2pEnabled = MutableStateFlow(false)
    val isWifiP2pEnabled: StateFlow<Boolean> = _isWifiP2pEnabled.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<WifiDirectPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<WifiDirectPeer>> = _discoveredPeers.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    private val _groupInfo = MutableStateFlow<WifiDirectGroup?>(null)
    val groupInfo: StateFlow<WifiDirectGroup?> = _groupInfo.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    fun isSupported(): Boolean = wifiP2pManager != null

    fun initialize() {
        if (channel != null) return

        channel = wifiP2pManager?.initialize(context, Looper.getMainLooper()) {
            Timber.w("WiFi P2P channel disconnected")
            channel = null
        }

        registerReceiver()
        Timber.d("WiFi Direct Manager initialized")
    }

    fun cleanup() {
        stopDiscovery()
        disconnect()
        unregisterReceiver()
        channel = null
        Timber.d("WiFi Direct Manager cleaned up")
    }

    private fun registerReceiver() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        )
                        _isWifiP2pEnabled.value = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        Timber.d("WiFi P2P state changed: enabled=${_isWifiP2pEnabled.value}")
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        requestPeers()
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        requestConnectionInfo()
                        requestGroupInfo()
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                                WifiP2pDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        }
                        Timber.d("This device changed: ${device?.deviceName}")
                    }
                }
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        context.registerReceiver(receiver, intentFilter)
    }

    private fun unregisterReceiver() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Timber.w("Receiver not registered")
            }
        }
        receiver = null
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _isDiscovering.value = true
                Timber.d("WiFi P2P discovery started")
            }

            override fun onFailure(reason: Int) {
                _isDiscovering.value = false
                Timber.e("WiFi P2P discovery failed: ${getFailureReason(reason)}")
            }
        })
    }

    fun stopDiscovery() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        manager.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _isDiscovering.value = false
                Timber.d("WiFi P2P discovery stopped")
            }

            override fun onFailure(reason: Int) {
                Timber.e("Failed to stop discovery: ${getFailureReason(reason)}")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        manager.requestPeers(ch) { peers ->
            val peerList = peers?.deviceList?.map { WifiDirectPeer.fromWifiP2pDevice(it) } ?: emptyList()
            _discoveredPeers.value = peerList
            Timber.d("Discovered ${peerList.size} peers")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        manager.requestConnectionInfo(ch) { info ->
            _connectionInfo.value = info
            Timber.d("Connection info: groupFormed=${info?.groupFormed}, isGroupOwner=${info?.isGroupOwner}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupInfo() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        manager.requestGroupInfo(ch) { group ->
            _groupInfo.value = group?.let { WifiDirectGroup.fromWifiP2pGroup(it) }
            Timber.d("Group info: ${group?.networkName}, clients=${group?.clientList?.size ?: 0}")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(deviceAddress: String): Flow<Boolean> = callbackFlow {
        val manager = wifiP2pManager
        val ch = this@WifiDirectManager.channel

        if (manager == null || ch == null) {
            trySend(false)
            close()
            return@callbackFlow
        }

        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }

        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("Connection initiated to $deviceAddress")
                trySend(true)
            }

            override fun onFailure(reason: Int) {
                Timber.e("Connection failed: ${getFailureReason(reason)}")
                trySend(false)
            }
        })

        awaitClose()
    }

    @SuppressLint("MissingPermission")
    fun createGroup(): Flow<Boolean> = callbackFlow {
        val manager = wifiP2pManager
        val ch = this@WifiDirectManager.channel

        if (manager == null || ch == null) {
            trySend(false)
            close()
            return@callbackFlow
        }

        manager.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("Group created successfully")
                trySend(true)
            }

            override fun onFailure(reason: Int) {
                Timber.e("Failed to create group: ${getFailureReason(reason)}")
                trySend(false)
            }
        })

        awaitClose()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _connectionInfo.value = null
                _groupInfo.value = null
                Timber.d("Disconnected from group")
            }

            override fun onFailure(reason: Int) {
                Timber.e("Failed to disconnect: ${getFailureReason(reason)}")
            }
        })
    }

    fun getGroupOwnerAddress(): String? {
        return connectionInfo.value?.groupOwnerAddress?.hostAddress
    }

    fun isGroupOwner(): Boolean {
        return connectionInfo.value?.isGroupOwner == true
    }

    fun isConnected(): Boolean {
        return connectionInfo.value?.groupFormed == true
    }

    private fun getFailureReason(reason: Int): String {
        return when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P not supported"
            WifiP2pManager.ERROR -> "Internal error"
            WifiP2pManager.BUSY -> "Framework busy"
            else -> "Unknown error ($reason)"
        }
    }
}
