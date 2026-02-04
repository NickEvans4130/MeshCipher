package com.meshcipher.presentation.wifidirect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.wifidirect.WifiDirectManager
import com.meshcipher.data.wifidirect.WifiDirectSocketManager
import com.meshcipher.domain.model.WifiDirectGroup
import com.meshcipher.domain.model.WifiDirectPeer
import com.meshcipher.util.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WifiDirectViewModel @Inject constructor(
    private val application: Application,
    private val wifiDirectManager: WifiDirectManager,
    private val socketManager: WifiDirectSocketManager
) : AndroidViewModel(application) {

    val isWifiP2pEnabled: StateFlow<Boolean> = wifiDirectManager.isWifiP2pEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val discoveredPeers: StateFlow<List<WifiDirectPeer>> = wifiDirectManager.discoveredPeers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groupInfo: StateFlow<WifiDirectGroup?> = wifiDirectManager.groupInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isDiscovering: StateFlow<Boolean> = wifiDirectManager.isDiscovering
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isServerRunning: StateFlow<Boolean> = socketManager.isServerRunning
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val connectedClients: StateFlow<Set<String>> = socketManager.connectedClients
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _hasPermissions = MutableStateFlow(false)
    val hasPermissions: StateFlow<Boolean> = _hasPermissions.asStateFlow()

    init {
        checkPermissions()
        if (wifiDirectManager.isSupported()) {
            wifiDirectManager.initialize()
        }
    }

    fun checkPermissions() {
        _hasPermissions.value = PermissionUtils.hasWifiDirectPermissions(application)
    }

    fun isSupported(): Boolean = wifiDirectManager.isSupported()

    fun isConnected(): Boolean = wifiDirectManager.isConnected()

    fun isGroupOwner(): Boolean = wifiDirectManager.isGroupOwner()

    fun startDiscovery() {
        wifiDirectManager.startDiscovery()
    }

    fun stopDiscovery() {
        wifiDirectManager.stopDiscovery()
    }

    fun connectToPeer(deviceAddress: String) {
        viewModelScope.launch {
            _isConnecting.value = true
            wifiDirectManager.connect(deviceAddress).collect { success ->
                _isConnecting.value = false
            }
        }
    }

    fun createGroup() {
        viewModelScope.launch {
            _isConnecting.value = true
            wifiDirectManager.createGroup().collect { success ->
                _isConnecting.value = false
            }
        }
    }

    fun disconnect() {
        socketManager.stopServer()
        socketManager.disconnectAll()
        wifiDirectManager.disconnect()
    }

    fun getRequiredPermissions(): Array<String> = PermissionUtils.getWifiDirectPermissions()

    override fun onCleared() {
        super.onCleared()
        wifiDirectManager.cleanup()
    }
}
