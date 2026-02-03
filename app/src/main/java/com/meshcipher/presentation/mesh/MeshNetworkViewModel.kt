package com.meshcipher.presentation.mesh

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.bluetooth.BluetoothMeshManager
import com.meshcipher.data.bluetooth.BluetoothMeshService
import com.meshcipher.data.bluetooth.routing.MeshRouter
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.domain.model.MeshPeer
import com.meshcipher.util.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MeshNetworkViewModel @Inject constructor(
    private val application: Application,
    private val bluetoothMeshManager: BluetoothMeshManager,
    private val meshRouter: MeshRouter,
    private val appPreferences: AppPreferences
) : AndroidViewModel(application) {

    val discoveredPeers: StateFlow<List<MeshPeer>> = bluetoothMeshManager.discoveredPeers
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val isScanning: StateFlow<Boolean> = bluetoothMeshManager.isScanning
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val isAdvertising: StateFlow<Boolean> = bluetoothMeshManager.isAdvertising
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val meshEnabled: StateFlow<Boolean> = appPreferences.meshEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    private val _hasPermissions = MutableStateFlow(false)
    val hasPermissions: StateFlow<Boolean> = _hasPermissions.asStateFlow()

    init {
        checkPermissions()
    }

    fun checkPermissions() {
        _hasPermissions.value = PermissionUtils.hasBluetoothPermissions(application)
    }

    fun isBluetoothEnabled(): Boolean = bluetoothMeshManager.isBluetoothEnabled()

    fun isBluetoothSupported(): Boolean = bluetoothMeshManager.isBluetoothSupported()

    fun setMeshEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.setMeshEnabled(enabled)
            if (enabled) {
                BluetoothMeshService.start(application)
            } else {
                BluetoothMeshService.stop(application)
            }
        }
    }

    fun startScanning() {
        viewModelScope.launch(Dispatchers.IO) {
            bluetoothMeshManager.startScanning()
        }
    }

    fun stopScanning() {
        bluetoothMeshManager.stopScanning()
    }

    fun getRouteCount(): Int = meshRouter.routingTable.getAllRoutes().size
}
