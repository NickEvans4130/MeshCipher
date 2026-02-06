package com.meshcipher.presentation.p2ptor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.tor.EmbeddedTorManager
import com.meshcipher.data.tor.P2PConnectionManager
import com.meshcipher.data.tor.P2PTorService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class P2PTorViewModel @Inject constructor(
    private val application: Application,
    private val p2pConnectionManager: P2PConnectionManager
) : AndroidViewModel(application) {

    val torStatus: StateFlow<EmbeddedTorManager.EmbeddedTorStatus> =
        p2pConnectionManager.torStatus
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = EmbeddedTorManager.EmbeddedTorStatus()
            )

    fun startP2PTor() {
        P2PTorService.start(application)
    }

    fun stopP2PTor() {
        P2PTorService.stop(application)
    }

    fun isRunning(): Boolean = p2pConnectionManager.isRunning()
}
