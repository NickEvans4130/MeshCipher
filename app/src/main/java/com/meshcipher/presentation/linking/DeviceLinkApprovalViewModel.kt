package com.meshcipher.presentation.linking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.linking.LinkConfirmationChannel
import com.meshcipher.data.local.database.ContactDao
import com.meshcipher.data.remote.api.RelayApiService
import com.meshcipher.data.remote.dto.ConsumeNonceRequest
import com.meshcipher.data.repository.LinkedDevicesRepository
import com.meshcipher.data.transport.ContentTypes
import com.meshcipher.data.transport.InternetTransport
import com.meshcipher.shared.domain.model.DeviceLinkRequest
import com.meshcipher.shared.domain.model.DeviceLinkResponse
import com.meshcipher.shared.domain.model.DeviceType
import com.meshcipher.shared.domain.model.LinkedDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject

sealed class ApprovalState {
    object Idle : ApprovalState()
    object Loading : ApprovalState()
    /** Phone approved the request; awaiting desktop confirmation (GAP-06 / R-06). */
    object AwaitingDesktopConfirmation : ApprovalState()
    object Approved : ApprovalState()
    object Denied : ApprovalState()
    data class Error(val message: String) : ApprovalState()
}

private const val DESKTOP_CONFIRMATION_TIMEOUT_MS = 2 * 60 * 1000L // 2 minutes

@HiltViewModel
class DeviceLinkApprovalViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val repository: LinkedDevicesRepository,
    private val internetTransport: InternetTransport,
    private val relayApiService: RelayApiService,
    private val linkConfirmationChannel: LinkConfirmationChannel,
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
                val nonce = request.nonce
                if (!nonce.isNullOrBlank()) {
                    val nonceResult = relayApiService.consumeNonce(
                        ConsumeNonceRequest(nonce = nonce, deviceId = request.deviceId)
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

                // GAP-06 / R-06: Store device as pending (approved=false) until the desktop
                // confirms. The device does not become active until LINK_CONFIRMED is received.
                repository.upsert(
                    LinkedDevice(
                        deviceId = request.deviceId,
                        deviceName = request.deviceName,
                        deviceType = request.deviceType ?: DeviceType.DESKTOP,
                        publicKeyHex = request.publicKeyHex,
                        linkedAt = System.currentTimeMillis(),
                        approved = false
                    )
                )

                val desktopUserId = userIdFromPublicKeyHex(request.publicKeyHex)

                // Send confirmation request to the desktop (content_type=18).
                // Payload includes device name, public key fingerprint, timestamp, and nonce
                // so the desktop can show a meaningful confirmation dialog and tie the event
                // to this specific enrolment session.
                val confirmationPayload = gson.toJson(
                    mapOf<String, Any>(
                        "deviceId" to identity.deviceId,
                        "deviceName" to identity.deviceName,
                        "publicKeyFingerprint" to phonePublicKeyHex.take(16),
                        "publicKeyHex" to phonePublicKeyHex,
                        "timestamp" to System.currentTimeMillis(),
                        "nonce" to (request.nonce ?: "")
                    )
                )
                internetTransport.sendMessage(
                    senderId = identity.userId,
                    recipientId = desktopUserId,
                    encryptedContent = confirmationPayload.toByteArray(),
                    contentType = ContentTypes.LINK_CONFIRM_REQUEST
                )

                // GAP-06 / R-06: Clear any stale replay event from a prior session before
                // waiting, so a cached Confirmed/Denied from a previous enrolment cannot
                // immediately satisfy the collector for this new request.
                linkConfirmationChannel.resetReplay()

                _state.value = ApprovalState.AwaitingDesktopConfirmation

                // Wait for desktop to confirm or deny within 2 minutes (GAP-06 / R-06).
                try {
                    withTimeout(DESKTOP_CONFIRMATION_TIMEOUT_MS) {
                        linkConfirmationChannel.events
                            .first { event ->
                                (event is LinkConfirmationChannel.Event.Confirmed &&
                                    event.deviceId == request.deviceId) ||
                                (event is LinkConfirmationChannel.Event.Denied &&
                                    event.deviceId == request.deviceId)
                            }
                    }.let { event ->
                        when (event) {
                            is LinkConfirmationChannel.Event.Confirmed -> {
                                // Desktop confirmed — mark the device as fully approved
                                repository.approve(request.deviceId)
                                // Now send the DeviceLinkResponse (type=10) so desktop
                                // can store the phone's identity details
                                val linkResponse = DeviceLinkResponse(
                                    approved = true,
                                    phoneDeviceId = identity.deviceId,
                                    phoneUserId = identity.userId,
                                    phonePublicKeyHex = phonePublicKeyHex
                                )
                                internetTransport.sendMessage(
                                    senderId = identity.userId,
                                    recipientId = desktopUserId,
                                    encryptedContent = gson.toJson(linkResponse).toByteArray(),
                                    contentType = ContentTypes.DEVICE_LINK
                                )
                                sendContactSync(
                                    desktopDeviceId = desktopUserId,
                                    senderUserId = identity.userId
                                )
                                _state.value = ApprovalState.Approved
                            }
                            is LinkConfirmationChannel.Event.Denied -> {
                                repository.revoke(request.deviceId)
                                _state.value = ApprovalState.Denied
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    // Desktop did not confirm within 2 minutes — cancel the pending link
                    repository.revoke(request.deviceId)
                    error("Desktop did not confirm within 2 minutes. The link request has been cancelled.")
                }
            }.fold(
                onSuccess = { /* state already set in the block above */ },
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
                    contentType = ContentTypes.DEVICE_LINK
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
            contentType = ContentTypes.CONTACT_SYNC
        )
    }

    private fun userIdFromPublicKeyHex(publicKeyHex: String): String {
        val pubKeyBytes = publicKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(pubKeyBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).take(32)
    }
}
