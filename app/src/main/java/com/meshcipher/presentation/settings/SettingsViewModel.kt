package com.meshcipher.presentation.settings

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.bluetooth.BluetoothMeshManager
import com.meshcipher.data.bluetooth.BluetoothMeshService
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.data.tor.TorManager
import com.meshcipher.domain.model.ConnectionMode
import com.meshcipher.domain.model.MessageExpiryMode
import com.meshcipher.util.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val application: Application,
    private val appPreferences: AppPreferences,
    private val torManager: TorManager,
    private val bluetoothMeshManager: BluetoothMeshManager,
    private val identityManager: IdentityManager
) : AndroidViewModel(application) {

    val connectionMode: StateFlow<ConnectionMode> = appPreferences.connectionMode
        .map { name ->
            try {
                ConnectionMode.valueOf(name)
            } catch (e: IllegalArgumentException) {
                ConnectionMode.DIRECT
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ConnectionMode.DIRECT
        )

    val torStatus: StateFlow<TorManager.TorStatus> = torManager.status
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = TorManager.TorStatus()
        )

    val meshEnabled: StateFlow<Boolean> = appPreferences.meshEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val messageExpiryMode: StateFlow<MessageExpiryMode> = appPreferences.messageExpiryMode
        .map { name -> MessageExpiryMode.fromNameOrDefault(name) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = MessageExpiryMode.NEVER
        )

    private val _hasBluetoothPermissions = MutableStateFlow(false)
    val hasBluetoothPermissions: StateFlow<Boolean> = _hasBluetoothPermissions.asStateFlow()

    private val _userId = MutableStateFlow<String?>(null)
    val userId: StateFlow<String?> = _userId.asStateFlow()

    init {
        checkBluetoothPermissions()
        loadUserId()
    }

    private fun loadUserId() {
        viewModelScope.launch(Dispatchers.IO) {
            val identity = identityManager.getIdentity()
            _userId.value = identity?.userId
        }
    }

    fun setConnectionMode(mode: ConnectionMode) {
        viewModelScope.launch {
            appPreferences.setConnectionMode(mode.name)
        }
    }

    fun refreshTorStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            torManager.refreshStatus()
        }
    }

    fun getOrbotInstallIntent(): Intent = torManager.getOrbotInstallIntent()

    fun getOrbotLaunchIntent(): Intent? = torManager.getOrbotLaunchIntent()

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

    fun checkBluetoothPermissions() {
        _hasBluetoothPermissions.value = PermissionUtils.hasBluetoothPermissions(application)
    }

    fun hasNotificationPermission(): Boolean = PermissionUtils.hasNotificationPermission(application)

    fun isBluetoothEnabled(): Boolean = bluetoothMeshManager.isBluetoothEnabled()

    fun isBluetoothSupported(): Boolean = bluetoothMeshManager.isBluetoothSupported()

    fun getRequiredPermissions(): Array<String> = PermissionUtils.getAllMeshPermissions()

    fun setMessageExpiryMode(mode: MessageExpiryMode) {
        viewModelScope.launch {
            appPreferences.setMessageExpiryMode(mode.name)
        }
    }
}
