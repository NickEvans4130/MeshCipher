package com.meshcipher.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.tor.TorBridge
import com.meshcipher.data.tor.TorBridgeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject

sealed class BridgeFetchState {
    object Idle : BridgeFetchState()
    object Loading : BridgeFetchState()
    data class Success(val count: Int) : BridgeFetchState()
    data class Error(val message: String) : BridgeFetchState()
}

@HiltViewModel
class BridgeSettingsViewModel @Inject constructor(
    private val bridgeRepository: TorBridgeRepository
) : ViewModel() {

    val bridges: StateFlow<List<TorBridge>> = bridgeRepository.bridges

    private val _fetchState = MutableStateFlow<BridgeFetchState>(BridgeFetchState.Idle)
    val fetchState: StateFlow<BridgeFetchState> = _fetchState.asStateFlow()

    private val _validationError = MutableStateFlow<String?>(null)
    val validationError: StateFlow<String?> = _validationError.asStateFlow()

    /** Returns true if the bridge was valid and added, false if validation failed. */
    fun addBridgeLine(line: String): Boolean {
        val error = TorBridge.validate(line)
        if (error != null) {
            _validationError.value = error
            return false
        }
        _validationError.value = null
        val bridge = TorBridge.parse(line) ?: return false
        bridgeRepository.addBridge(bridge)
        return true
    }

    fun removeBridge(bridge: TorBridge) {
        bridgeRepository.removeBridge(bridge)
    }

    fun clearValidationError() {
        _validationError.value = null
    }

    /**
     * GAP-09 / R-11: Fetch obfs4 bridges from the Tor Project bridge server.
     * Falls back gracefully if the request is blocked.
     */
    fun fetchBridgesFromTorProject() {
        _fetchState.value = BridgeFetchState.Loading
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val request = Request.Builder()
                        .url("https://bridges.torproject.org/bridges?transport=obfs4")
                        .header("User-Agent", "MeshCipher/1.0")
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            return@withContext Result.failure<List<TorBridge>>(
                                Exception("HTTP ${response.code}")
                            )
                        }
                        val body = response.body?.string() ?: ""
                        val bridges = body.lines()
                            .map { it.trim() }
                            .filter { it.startsWith("obfs4") }
                            .mapNotNull { TorBridge.parse(it) }
                        Result.success(bridges)
                    }
                }
                result.onSuccess { newBridges ->
                    newBridges.forEach { bridgeRepository.addBridge(it) }
                    _fetchState.value = BridgeFetchState.Success(newBridges.size)
                }.onFailure { e ->
                    Timber.w(e, "Bridge fetch failed")
                    _fetchState.value = BridgeFetchState.Error(
                        "Bridge fetch failed — enter bridges manually"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Bridge fetch error")
                _fetchState.value = BridgeFetchState.Error(
                    "Bridge fetch failed — enter bridges manually"
                )
            }
        }
    }
}
