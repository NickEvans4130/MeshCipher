package com.meshcipher.presentation.linking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.repository.LinkedDevicesRepository
import com.meshcipher.data.transport.InternetTransport
import com.meshcipher.shared.domain.model.DeviceLinkRequest
import com.meshcipher.shared.domain.model.DeviceLinkResponse
import com.meshcipher.shared.domain.model.LinkedDevice
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed class ApprovalState {
    object Idle : ApprovalState()
    object Loading : ApprovalState()
    object Approved : ApprovalState()
    object Denied : ApprovalState()
    data class Error(val message: String) : ApprovalState()
}

@HiltViewModel
class DeviceLinkApprovalViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val repository: LinkedDevicesRepository,
    private val internetTransport: InternetTransport
) : ViewModel() {

    private val _state = MutableStateFlow<ApprovalState>(ApprovalState.Idle)
    val state: StateFlow<ApprovalState> = _state.asStateFlow()

    private val gson = Gson()

    fun approve(request: DeviceLinkRequest) {
        viewModelScope.launch {
            _state.value = ApprovalState.Loading
            runCatching {
                val identity = identityManager.getIdentity()
                    ?: error("No local identity found")

                val phonePublicKeyHex = identity.hardwarePublicKey
                    .joinToString("") { "%02x".format(it) }

                // Store the linked device locally
                repository.upsert(
                    LinkedDevice(
                        deviceId = request.deviceId,
                        deviceName = request.deviceName,
                        deviceType = request.deviceType,
                        publicKeyHex = request.publicKeyHex,
                        linkedAt = System.currentTimeMillis(),
                        approved = true
                    )
                )

                // Send approval response back to the desktop via relay
                val response = DeviceLinkResponse(
                    approved = true,
                    phoneDeviceId = identity.deviceId,
                    phoneUserId = identity.userId,
                    phonePublicKeyHex = phonePublicKeyHex
                )
                val responseJson = gson.toJson(response)
                internetTransport.sendMessage(
                    senderId = identity.userId,
                    recipientId = request.deviceId,
                    encryptedContent = responseJson.toByteArray(),
                    contentType = 10 // device link response type
                )
            }.fold(
                onSuccess = { _state.value = ApprovalState.Approved },
                onFailure = { e ->
                    Timber.e(e, "Device link approval failed")
                    _state.value = ApprovalState.Error(e.message ?: "Approval failed")
                }
            )
        }
    }

    fun deny(request: DeviceLinkRequest) {
        viewModelScope.launch {
            _state.value = ApprovalState.Loading
            runCatching {
                val identity = identityManager.getIdentity() ?: return@runCatching
                val response = DeviceLinkResponse(
                    approved = false,
                    phoneDeviceId = identity.deviceId,
                    phoneUserId = identity.userId
                )
                val responseJson = gson.toJson(response)
                internetTransport.sendMessage(
                    senderId = identity.userId,
                    recipientId = request.deviceId,
                    encryptedContent = responseJson.toByteArray(),
                    contentType = 10
                )
            }.fold(
                onSuccess = { _state.value = ApprovalState.Denied },
                onFailure = { e ->
                    Timber.e(e, "Device link denial failed")
                    _state.value = ApprovalState.Denied // navigate away regardless
                }
            )
        }
    }
}
