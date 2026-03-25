package com.meshcipher.presentation.linking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.local.database.ContactDao
import com.meshcipher.data.repository.LinkedDevicesRepository
import com.meshcipher.data.remote.api.RelayApiService
import com.meshcipher.data.remote.dto.ConsumeNonceRequest
import com.meshcipher.data.transport.InternetTransport
import com.meshcipher.shared.domain.model.DeviceLinkRequest
import com.meshcipher.shared.domain.model.DeviceLinkResponse
import com.meshcipher.shared.domain.model.DeviceType
import com.meshcipher.shared.domain.model.LinkedDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val internetTransport: InternetTransport,
    private val relayApiService: RelayApiService,
    private val contactDao: ContactDao
) : ViewModel() {

    private val _state = MutableStateFlow<ApprovalState>(ApprovalState.Idle)
    val state: StateFlow<ApprovalState> = _state.asStateFlow()

    private val gson = Gson()

    fun approve(request: DeviceLinkRequest) {
        viewModelScope.launch {
            _state.value = ApprovalState.Loading
            runCatching {
                // GAP-05 / R-06: Consume the one-time nonce to prevent QR replay.
                // The relay returns 409 if the nonce was already used.
                if (request.nonce.isNotBlank()) {
                    val nonceResult = relayApiService.consumeNonce(
                        ConsumeNonceRequest(nonce = request.nonce, deviceId = request.deviceId)
                    )
                    if (!nonceResult.isSuccessful) {
                        val code = nonceResult.code()
                        if (code == 409) error("This QR code has already been used. Please regenerate it.")
                        else error("Nonce validation failed (HTTP $code). Please try again.")
                    }
                }

                val identity = identityManager.getIdentity()
                    ?: error("No local identity found")

                val phonePublicKeyHex = identity.hardwarePublicKey
                    .joinToString("") { "%02x".format(it) }

                // Store the linked device locally
                repository.upsert(
                    LinkedDevice(
                        deviceId = request.deviceId,
                        deviceName = request.deviceName,
                        deviceType = request.deviceType ?: DeviceType.DESKTOP,
                        publicKeyHex = request.publicKeyHex,
                        linkedAt = System.currentTimeMillis(),
                        approved = true
                    )
                )

                // Send approval response to the desktop via relay
                val response = DeviceLinkResponse(
                    approved = true,
                    phoneDeviceId = identity.deviceId,
                    phoneUserId = identity.userId,
                    phonePublicKeyHex = phonePublicKeyHex
                )
                val desktopUserId = userIdFromPublicKeyHex(request.publicKeyHex)
                internetTransport.sendMessage(
                    senderId = identity.userId,
                    recipientId = desktopUserId,
                    encryptedContent = gson.toJson(response).toByteArray(),
                    contentType = 10
                )

                // Push all contacts to the desktop so they appear immediately
                sendContactSync(
                    desktopDeviceId = desktopUserId,
                    senderUserId = identity.userId
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
                internetTransport.sendMessage(
                    senderId = identity.userId,
                    recipientId = userIdFromPublicKeyHex(request.publicKeyHex),
                    encryptedContent = gson.toJson(response).toByteArray(),
                    contentType = 10
                )
            }.fold(
                onSuccess = { _state.value = ApprovalState.Denied },
                onFailure = { e ->
                    Timber.e(e, "Device link denial failed")
                    _state.value = ApprovalState.Denied
                }
            )
        }
    }

    private suspend fun sendContactSync(desktopDeviceId: String, senderUserId: String) {
        val contacts = contactDao.getAllContacts().first()
        if (contacts.isEmpty()) return

        val items = contacts.map { entity ->
            mapOf(
                "userId" to entity.id,
                "displayName" to entity.displayName,
                "publicKeyHex" to entity.publicKey.joinToString("") { "%02x".format(it) }
            )
        }
        val payload = mapOf("type" to "contact_sync", "contacts" to items)
        val payloadBytes = gson.toJson(payload).toByteArray()

        internetTransport.sendMessage(
            senderId = senderUserId,
            recipientId = desktopDeviceId,
            encryptedContent = payloadBytes,
            contentType = 11
        )
    }

    private fun userIdFromPublicKeyHex(publicKeyHex: String): String {
        val pubKeyBytes = publicKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(pubKeyBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).take(32)
    }
}
