package com.meshcipher.presentation.linking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.meshcipher.shared.domain.model.DeviceLinkRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Base64
import javax.inject.Inject

sealed class QRScanState {
    object Scanning : QRScanState()
    data class Scanned(val request: DeviceLinkRequest) : QRScanState()
    data class Error(val message: String) : QRScanState()
}

@HiltViewModel
class QRScannerViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow<QRScanState>(QRScanState.Scanning)
    val state: StateFlow<QRScanState> = _state.asStateFlow()

    private val gson = Gson()

    fun onQrScanned(rawValue: String) {
        if (_state.value is QRScanState.Scanned) return
        viewModelScope.launch {
            runCatching {
                val prefix = "meshcipher://link/"
                require(rawValue.startsWith(prefix)) { "Not a device link QR code" }
                val encoded = rawValue.removePrefix(prefix)
                val jsonBytes = Base64.getUrlDecoder().decode(encoded)
                gson.fromJson(String(jsonBytes), DeviceLinkRequest::class.java)
                    ?: error("Failed to parse link request")
            }.fold(
                onSuccess = { request -> _state.value = QRScanState.Scanned(request) },
                onFailure = { e -> _state.value = QRScanState.Error(e.message ?: "Invalid QR code") }
            )
        }
    }

    fun reset() { _state.value = QRScanState.Scanning }
}
