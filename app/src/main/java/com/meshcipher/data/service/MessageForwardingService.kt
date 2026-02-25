package com.meshcipher.data.service

import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.repository.LinkedDevicesRepository
import com.meshcipher.data.transport.InternetTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Forwards outgoing messages to all approved linked desktop devices.
 *
 * When the Android app sends a message, this service delivers a copy
 * of the encrypted payload to each approved linked device via the relay.
 * The desktop app receives it through its WebSocket connection and displays it.
 */
@Singleton
class MessageForwardingService @Inject constructor(
    private val identityManager: IdentityManager,
    private val linkedDevicesRepository: LinkedDevicesRepository,
    private val internetTransport: InternetTransport
) {

    /**
     * Forward [encryptedContent] to all approved linked devices.
     * Call this after a message has been sent/encrypted on the Android side.
     */
    suspend fun forwardToLinkedDevices(
        encryptedContent: ByteArray,
        contentType: Int = 0
    ) = withContext(Dispatchers.IO) {
        val identity = identityManager.getIdentity() ?: return@withContext
        val approvedDevices = linkedDevicesRepository.observeApproved().first()

        if (approvedDevices.isEmpty()) return@withContext

        for (device in approvedDevices) {
            runCatching {
                internetTransport.sendMessage(
                    senderId = identity.userId,
                    recipientId = device.deviceId,
                    encryptedContent = encryptedContent,
                    contentType = contentType
                )
            }.onFailure { e ->
                Timber.w(e, "Failed to forward message to linked device %s", device.deviceId)
            }
        }
    }

    /**
     * Forward an incoming message received from a contact to all linked desktop devices,
     * so they display it in the conversation thread.
     */
    suspend fun forwardIncomingToLinkedDevices(
        fromUserId: String,
        encryptedContent: ByteArray,
        contentType: Int = 0
    ) = withContext(Dispatchers.IO) {
        val identity = identityManager.getIdentity() ?: return@withContext
        val approvedDevices = linkedDevicesRepository.observeApproved().first()

        if (approvedDevices.isEmpty()) return@withContext

        for (device in approvedDevices) {
            runCatching {
                internetTransport.sendMessage(
                    senderId = fromUserId,
                    recipientId = device.deviceId,
                    encryptedContent = encryptedContent,
                    contentType = contentType
                )
            }.onFailure { e ->
                Timber.w(e, "Failed to forward incoming message to linked device %s", device.deviceId)
            }
        }
    }
}
