package com.meshcipher.data.service

import com.google.gson.Gson
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.repository.LinkedDevicesRepository
import com.meshcipher.data.transport.InternetTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Forwarded message envelope sent to linked desktop devices (content_type=12).
 * Includes full conversation context so the desktop can place the message correctly.
 */
data class ForwardedMessage(
    val content: String,
    val contactId: String,
    val contactName: String,
    val isOutgoing: Boolean,
    val timestamp: Long
)

@Singleton
class MessageForwardingService @Inject constructor(
    private val identityManager: IdentityManager,
    private val linkedDevicesRepository: LinkedDevicesRepository,
    private val internetTransport: InternetTransport
) {
    private val gson = Gson()

    /**
     * Forward an outgoing message (you sent it) to all linked desktop devices.
     * [contactId] / [contactName] identify who you were talking to.
     */
    suspend fun forwardOutgoingToLinkedDevices(
        content: String,
        contactId: String,
        contactName: String
    ) = withContext(Dispatchers.IO) {
        val identity = identityManager.getIdentity() ?: return@withContext
        val approvedDevices = linkedDevicesRepository.observeApproved().first()
        if (approvedDevices.isEmpty()) return@withContext

        val payload = ForwardedMessage(
            content = content,
            contactId = contactId,
            contactName = contactName,
            isOutgoing = true,
            timestamp = System.currentTimeMillis()
        )
        val bytes = gson.toJson(payload).toByteArray()

        for (device in approvedDevices) {
            runCatching {
                internetTransport.sendMessage(
                    senderId = identity.userId,
                    recipientId = userIdFromPublicKeyHex(device.publicKeyHex),
                    encryptedContent = bytes,
                    contentType = 12
                )
            }.onFailure { e ->
                Timber.w(e, "Failed to forward outgoing message to linked device %s", device.deviceId)
            }
        }
    }

    /**
     * Forward an incoming message (contact sent it to you) to all linked desktop devices.
     */
    suspend fun forwardIncomingToLinkedDevices(
        content: String,
        contactId: String,
        contactName: String
    ) = withContext(Dispatchers.IO) {
        val identity = identityManager.getIdentity() ?: return@withContext
        val approvedDevices = linkedDevicesRepository.observeApproved().first()
        if (approvedDevices.isEmpty()) return@withContext

        val payload = ForwardedMessage(
            content = content,
            contactId = contactId,
            contactName = contactName,
            isOutgoing = false,
            timestamp = System.currentTimeMillis()
        )
        val bytes = gson.toJson(payload).toByteArray()

        for (device in approvedDevices) {
            runCatching {
                internetTransport.sendMessage(
                    senderId = identity.userId,
                    recipientId = userIdFromPublicKeyHex(device.publicKeyHex),
                    encryptedContent = bytes,
                    contentType = 12
                )
            }.onFailure { e ->
                Timber.w(e, "Failed to forward incoming message to linked device %s", device.deviceId)
            }
        }
    }

    /**
     * Send a type-13 unlink notification to a specific linked device, then revoke it locally.
     * Called when the user revokes a device from the Android Linked Devices screen.
     */
    suspend fun sendUnlinkAndRevoke(device: com.meshcipher.shared.domain.model.LinkedDevice) =
        withContext(Dispatchers.IO) {
            val identity = identityManager.getIdentity() ?: return@withContext
            val desktopUserId = userIdFromPublicKeyHex(device.publicKeyHex)
            runCatching {
                internetTransport.sendMessage(
                    senderId = identity.userId,
                    recipientId = desktopUserId,
                    encryptedContent = "unlink".toByteArray(),
                    contentType = 13
                )
            }.onFailure { e ->
                Timber.w(e, "Failed to send unlink notification to %s", device.deviceId)
            }
            linkedDevicesRepository.revoke(device.deviceId)
        }

    /**
     * Revoke any linked device whose derived userId matches [senderUserId].
     * Called when the desktop sends us a type-13 unlink notification.
     */
    suspend fun revokeLinkedDeviceByUserId(senderUserId: String) = withContext(Dispatchers.IO) {
        val devices = linkedDevicesRepository.observeApproved().first()
        val match = devices.firstOrNull { userIdFromPublicKeyHex(it.publicKeyHex) == senderUserId }
        if (match != null) {
            linkedDevicesRepository.revoke(match.deviceId)
            Timber.d("Revoked linked device %s via remote unlink", match.deviceId)
        }
    }

    /** Derives the relay user_id from a device's public key hex (matches desktop IdentityManager). */
    private fun userIdFromPublicKeyHex(publicKeyHex: String): String {
        val pubKeyBytes = publicKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(pubKeyBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).take(32)
    }
}
