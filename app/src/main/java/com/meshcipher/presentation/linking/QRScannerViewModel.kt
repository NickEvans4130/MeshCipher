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
                val request = gson.fromJson(String(jsonBytes), DeviceLinkRequest::class.java)
                    ?: error("Failed to parse link request")

                // GAP-05 / R-06: Reject QR codes whose timestamp falls outside the validity
                // window. Explicit bounds comparisons avoid Long overflow that abs() subtraction
                // is vulnerable to with attacker-supplied large timestamps.
                val now = System.currentTimeMillis()
                require(request.timestamp in (now - MAX_QR_AGE_MS)..(now + MAX_QR_FUTURE_SKEW_MS)) {
                    "Link request expired. Please regenerate the QR code."
                }
                request
            }.fold(
                onSuccess = { request -> _state.value = QRScanState.Scanned(request) },
                onFailure = { e -> _state.value = QRScanState.Error(e.message ?: "Invalid QR code") }
            )
        }
    }

    fun reset() { _state.value = QRScanState.Scanning }

    companion object {
        private const val MAX_QR_AGE_MS = 5 * 60 * 1000L       // 5 minutes back
        private const val MAX_QR_FUTURE_SKEW_MS = 60 * 1000L   // 1 minute forward (clock skew)
    }
}
