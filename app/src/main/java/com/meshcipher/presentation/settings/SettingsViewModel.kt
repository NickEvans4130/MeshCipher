package com.meshcipher.presentation.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.data.tor.TorManager
import com.meshcipher.domain.model.ConnectionMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val torManager: TorManager
) : ViewModel() {

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
}
