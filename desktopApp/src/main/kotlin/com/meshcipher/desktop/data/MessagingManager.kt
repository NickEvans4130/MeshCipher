package com.meshcipher.desktop.data

import com.meshcipher.desktop.crypto.MessageCrypto
import com.meshcipher.desktop.crypto.MessageEnvelope
import com.meshcipher.desktop.network.RelayTransport
import com.meshcipher.desktop.network.normalize
import com.meshcipher.desktop.platform.DesktopPlatform
import com.meshcipher.shared.domain.model.DeviceLinkResponse
import com.meshcipher.shared.util.generateUUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Orchestrates the full send / receive message lifecycle.
 *
 * Handles three incoming message types from the relay:
 *  - contentType=0  → encrypted message → decrypt → emit to [newMessages]
 *  - contentType=10 → device link response → store linked device
 *  - contentType=11 → contact sync payload → upsert contacts, emit [contactsUpdated]
 *
 * Incoming messages from the relay server arrive in the format:
 *   {"type":"new_message","message":{"sender_id":"...","encrypted_content":"<base64>","content_type":N}}
 * which is normalised by [RelayMessage.normalize()] before processing.
 */
/**
 * Payload of a link confirmation request sent by Android (content_type=18).
 * GAP-06 / R-06: Carries enough context for the desktop to show a meaningful dialog.
 */
data class LinkConfirmationRequest(
    val deviceId: String,
    val deviceName: String,
    val publicKeyFingerprint: String,
    val publicKeyHex: String,
    val timestamp: Long,
    val nonce: String
)

class MessagingManager(
    private val localDeviceId: String,
    private val crypto: MessageCrypto,
    private val relay: RelayTransport
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    private val _newMessages = MutableSharedFlow<DesktopMessage>(extraBufferCapacity = 64)
    val newMessages: SharedFlow<DesktopMessage> = _newMessages.asSharedFlow()

    /** Emits Unit whenever the contacts list changes (after a sync). */
    private val _contactsUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val contactsUpdated: SharedFlow<Unit> = _contactsUpdated.asSharedFlow()

    /**
     * GAP-06 / R-06: Emits a [LinkConfirmationRequest] when Android sends content_type=18.
     * The desktop UI collects this to show a confirmation dialog.
     */
    private val _linkConfirmationPending = MutableSharedFlow<LinkConfirmationRequest>(extraBufferCapacity = 1)
    val linkConfirmationPending: SharedFlow<LinkConfirmationRequest> = _linkConfirmationPending.asSharedFlow()

    init {
        scope.launch { listenForIncoming() }
        scope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        while (true) {
            runCatching { relay.pollQueuedMessages() }
            kotlinx.coroutines.delay(3_000L)
        }
    }

    suspend fun sendMessage(content: String, recipientId: String): Result<DesktopMessage> =
        withContext(Dispatchers.IO) {
            val contact = ContactRepository.getContact(recipientId)
                ?: return@withContext Result.failure(IllegalStateException("Contact $recipientId not found"))

            val msgId = generateUUID()
            val localMsg = MessageRepository.save(
                contactId = recipientId,
                content = content,
                isOutgoing = true,
                status = "sending",
                messageId = msgId
            )

            // Use ECDH only if the contact has a real desktop public key (non-empty, non-zero).
            // Phone contacts synced from Android don't have desktop keys, so send plaintext
            // base64 (content_type=0) the same way the Android app does.
            val hasDesktopKey = contact.publicKeyHex.isNotBlank() &&
                contact.publicKeyHex.any { it != '0' }

            runCatching {
                if (hasDesktopKey) {
                    val envelope = crypto.encryptMessage(
                        plaintext = content,
                        recipientUserId = recipientId,
                        recipientPublicKeyHex = contact.publicKeyHex
                    )
                    relay.sendMessage(recipientId, envelope.toJson())
                } else {
                    // Phone contact — route through the linked phone so the message
                    // arrives from the phone's identity (which the recipient knows).
                    val phone = DeviceLinkManager.getApprovedDevices().firstOrNull()
                        ?: return@withContext Result.failure(IllegalStateException("No linked phone to route message through"))
                    val escapedContent = content.replace("\\", "\\\\").replace("\"", "\\\"")
                    val payload = """{"content":"$escapedContent","recipientId":"$recipientId","senderId":"${phone.phoneUserId}","conversationId":""}""".toByteArray()
                    relay.sendRawMessage(
                        recipientId = phone.phoneUserId,
                        payload = payload,
                        contentType = CONTENT_TYPE_DESKTOP_SEND_REQUEST
                    )
                }
                MessageRepository.updateStatus(msgId, "sent")
                localMsg.copy(status = "sent")
            }.also { result ->
                if (result.isFailure) MessageRepository.updateStatus(msgId, "failed")
            }
        }

    private suspend fun listenForIncoming() {
        relay.incomingMessages.collect { relayMsg ->
            if (relayMsg.type != "new_message" && relayMsg.type != "message") return@collect
            val normalized = relayMsg.normalize() ?: return@collect
            runCatching { dispatch(normalized) }
        }
    }

    private suspend fun dispatch(msg: com.meshcipher.desktop.network.NormalizedMessage) {
        // Payload from relay server is base64-encoded; desktop→desktop is raw JSON
        val payloadBytes = runCatching {
            Base64.getDecoder().decode(msg.rawPayload)
        }.getOrElse { msg.rawPayload.toByteArray() }

        when (msg.contentType) {
            CONTENT_TYPE_DEVICE_LINK -> handleDeviceLinkResponse(payloadBytes)
            CONTENT_TYPE_CONTACT_SYNC -> handleContactSync(payloadBytes)
            CONTENT_TYPE_FORWARDED -> handleForwardedMessage(payloadBytes)
            CONTENT_TYPE_DEVICE_UNLINK -> handleRemoteUnlink()
            CONTENT_TYPE_MEDIA_FORWARDED -> handleMediaForwarded(payloadBytes)
            CONTENT_TYPE_DESKTOP_MSG -> handleDesktopMessage(msg.senderId, payloadBytes)
            // GAP-06 / R-06: Android approved the link and is requesting desktop confirmation
            CONTENT_TYPE_LINK_CONFIRM_REQUEST -> handleLinkConfirmationRequest(payloadBytes)
            else -> handleMessage(msg.senderId, payloadBytes, msg.rawPayload)
        }
    }

    private fun handleDeviceLinkResponse(bytes: ByteArray) {
        val response = runCatching {
            json.decodeFromString<DeviceLinkResponse>(String(bytes))
        }.getOrNull() ?: return
        scope.launch {
            DeviceLinkManager.processLinkResponse(response)
            // Add the phone itself as a contact so incoming messages can be matched
            val pubKeyHex = response.phonePublicKeyHex
            if (response.approved && pubKeyHex != null) {
                ContactRepository.upsertContact(
                    contactId = response.phoneUserId,
                    displayName = "My Phone",
                    publicKeyHex = pubKeyHex
                )
                _contactsUpdated.emit(Unit)
            }
        }
    }

    private suspend fun handleContactSync(bytes: ByteArray) {
        val payload = runCatching {
            json.decodeFromString<ContactSyncPayload>(String(bytes))
        }.getOrNull() ?: return

        for (item in payload.contacts) {
            ContactRepository.upsertContact(
                contactId = item.userId,
                displayName = item.displayName,
                publicKeyHex = item.publicKeyHex
            )
        }
        _contactsUpdated.emit(Unit)
    }

    private suspend fun handleMessage(senderId: String, payloadBytes: ByteArray, @Suppress("UNUSED_PARAMETER") rawPayload: String) {
        // Auto-register unknown sender if it matches the linked phone
        var contact = ContactRepository.getContact(senderId)
        if (contact == null) {
            val linkedPhone = DeviceLinkManager.getApprovedDevices()
                .firstOrNull { it.phoneUserId == senderId } ?: return
            ContactRepository.upsertContact(
                contactId = senderId,
                displayName = "My Phone",
                publicKeyHex = linkedPhone.phonePublicKeyHex
            )
            contact = ContactRepository.getContact(senderId) ?: return
        }

        // Try as MessageEnvelope (ECDH-encrypted, desktop→desktop)
        val payloadJson = String(payloadBytes)
        val envelope = runCatching { MessageEnvelope.fromJson(payloadJson) }.getOrNull()

        val plaintext = if (envelope != null) {
            runCatching { crypto.decryptMessage(envelope, contact.publicKeyHex) }.getOrNull()
                ?: return // Decryption failed — wrong key or corrupted
        } else {
            // Phone forwarding sends plaintext bytes; treat as UTF-8 text directly
            payloadJson.trim().ifBlank { return }
        }

        val msg = MessageRepository.save(
            contactId = senderId,
            content = plaintext,
            isOutgoing = false,
            status = "delivered",
            timestamp = envelope?.timestamp ?: System.currentTimeMillis()
        )
        _newMessages.emit(msg)

        val senderName = contact.displayName.ifBlank { senderId.take(8) }
        DesktopPlatform.showNotification(
            title = senderName,
            body = plaintext.take(80).let { if (plaintext.length > 80) "$it…" else it }
        )
    }

    private suspend fun handleForwardedMessage(bytes: ByteArray) {
        val root = runCatching {
            json.parseToJsonElement(String(bytes)).let { it as? kotlinx.serialization.json.JsonObject }
        }.getOrNull() ?: return
        val content = root["content"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty().ifBlank { return }
        val contactId = root["contactId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty().ifBlank { return }
        val contactName = root["contactName"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty().ifBlank { contactId.take(8) }
        val isOutgoing = root["isOutgoing"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() } ?: false
        val timestamp = root["timestamp"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() } ?: System.currentTimeMillis()

        // Ensure contact exists so the conversation renders with the right name
        if (ContactRepository.getContact(contactId) == null) {
            ContactRepository.upsertContact(
                contactId = contactId,
                displayName = contactName,
                publicKeyHex = ""
            )
            _contactsUpdated.emit(Unit)
        }

        val msg = MessageRepository.save(
            contactId = contactId,
            content = content,
            isOutgoing = isOutgoing,
            status = "delivered",
            timestamp = timestamp
        )
        _newMessages.emit(msg)

        if (!isOutgoing) {
            DesktopPlatform.showNotification(
                title = contactName,
                body = content.take(80).let { if (content.length > 80) "$it…" else it }
            )
        }
    }

    private suspend fun handleMediaForwarded(bytes: ByteArray) {
        val root = runCatching {
            json.parseToJsonElement(String(bytes)).let { it as? kotlinx.serialization.json.JsonObject }
        }.getOrNull() ?: return

        fun str(key: String) = root[key]
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .orEmpty()

        val fileName = str("fileName").ifBlank { return }
        val mimeType = str("mimeType")
        val fileDataB64 = str("fileData").ifBlank { return }
        val contactId = str("contactId").ifBlank { return }
        val contactName = str("contactName").ifBlank { contactId.take(8) }
        val isOutgoing = root["isOutgoing"]
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() }
            ?: false
        val timestamp = root["timestamp"]
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() }
            ?: System.currentTimeMillis()

        val fileBytes = runCatching { java.util.Base64.getDecoder().decode(fileDataB64) }
            .getOrNull() ?: return

        // Save to ~/Downloads/MeshCipher/<contactName>/<fileName>
        val safeContact = contactName.replace(Regex("[^A-Za-z0-9_\\- ]"), "_")
        val safeFile = fileName.replace(Regex("[^A-Za-z0-9_.\\- ]"), "_")
        val downloadsDir = java.io.File(System.getProperty("user.home"), "Downloads/MeshCipher/$safeContact")
        downloadsDir.mkdirs()
        val outFile = java.io.File(downloadsDir, safeFile)
        outFile.writeBytes(fileBytes)

        if (ContactRepository.getContact(contactId) == null) {
            ContactRepository.upsertContact(
                contactId = contactId,
                displayName = contactName,
                publicKeyHex = ""
            )
            _contactsUpdated.emit(Unit)
        }

        val displayContent = "[File: ${outFile.absolutePath}]"
        val msg = MessageRepository.save(
            contactId = contactId,
            content = displayContent,
            isOutgoing = isOutgoing,
            status = "delivered",
            timestamp = timestamp
        )
        _newMessages.emit(msg)

        if (!isOutgoing) {
            DesktopPlatform.showNotification(
                title = contactName,
                body = "File received: $fileName"
            )
        }
    }

    /**
     * Send an ECDH-encrypted message to another linked desktop (content_type=15).
     * Uses the contact's stored public key hex for the ECDH key agreement.
     */
    suspend fun sendToDesktop(content: String, recipientId: String): Result<DesktopMessage> =
        withContext(Dispatchers.IO) {
            val contact = ContactRepository.getContact(recipientId)
                ?: return@withContext Result.failure(IllegalStateException("Contact $recipientId not found"))

            val hasKey = contact.publicKeyHex.isNotBlank() && contact.publicKeyHex.any { it != '0' }
            if (!hasKey) return@withContext Result.failure(
                IllegalStateException("No desktop public key for $recipientId")
            )

            val msgId = generateUUID()
            val localMsg = MessageRepository.save(
                contactId = recipientId,
                content = content,
                isOutgoing = true,
                status = "sending",
                messageId = msgId
            )

            runCatching {
                val envelope = crypto.encryptMessage(
                    plaintext = content,
                    recipientUserId = recipientId,
                    recipientPublicKeyHex = contact.publicKeyHex
                )
                relay.sendRawMessage(
                    recipientId = recipientId,
                    payload = envelope.toJson().toByteArray(),
                    contentType = CONTENT_TYPE_DESKTOP_MSG
                )
                MessageRepository.updateStatus(msgId, "sent")
                localMsg.copy(status = "sent")
            }.also { result ->
                if (result.isFailure) MessageRepository.updateStatus(msgId, "failed")
            }
        }

    /** Incoming ECDH-encrypted desktop-to-desktop message (content_type=15). */
    private suspend fun handleDesktopMessage(senderId: String, payloadBytes: ByteArray) {
        var contact = ContactRepository.getContact(senderId)
        if (contact == null) {
            val linkedPhone = DeviceLinkManager.getApprovedDevices()
                .firstOrNull { it.phoneUserId == senderId } ?: return
            ContactRepository.upsertContact(
                contactId = senderId,
                displayName = linkedPhone.deviceName,
                publicKeyHex = linkedPhone.phonePublicKeyHex
            )
            contact = ContactRepository.getContact(senderId) ?: return
        }

        val payloadJson = String(payloadBytes)
        val envelope = runCatching { MessageEnvelope.fromJson(payloadJson) }.getOrNull() ?: return
        val plaintext = runCatching {
            crypto.decryptMessage(envelope, contact.publicKeyHex)
        }.getOrNull() ?: return

        val msg = MessageRepository.save(
            contactId = senderId,
            content = plaintext,
            isOutgoing = false,
            status = "delivered",
            timestamp = envelope.timestamp
        )
        _newMessages.emit(msg)

        val senderName = contact.displayName.ifBlank { senderId.take(8) }
        DesktopPlatform.showNotification(
            title = senderName,
            body = plaintext.take(80).let { if (plaintext.length > 80) "$it…" else it }
        )
    }

    /**
     * GAP-06 / R-06: Android sent a link confirmation request (content_type=18).
     * Parse the payload and expose it so the UI can show a confirmation dialog.
     */
    private fun handleLinkConfirmationRequest(bytes: ByteArray) {
        runCatching {
            val root = json.parseToJsonElement(String(bytes)).let {
                it as? kotlinx.serialization.json.JsonObject
            } ?: return
            fun str(k: String) = root[k]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            }.orEmpty()
            val req = LinkConfirmationRequest(
                deviceId = str("deviceId"),
                deviceName = str("deviceName"),
                publicKeyFingerprint = str("publicKeyFingerprint"),
                publicKeyHex = str("publicKeyHex"),
                timestamp = root["timestamp"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                } ?: System.currentTimeMillis(),
                nonce = str("nonce")
            )
            if (req.deviceId.isBlank()) return
            _linkConfirmationPending.tryEmit(req)
        }.onFailure { e -> System.err.println("MessagingManager: Failed to parse link confirmation request: $e") }
    }

    /**
     * GAP-06 / R-06: Desktop user confirmed the link.
     * Sends content_type=19 to Android so it can mark the device as fully approved.
     * [phoneUserId] is the relay userId of the Android device.
     * [pendingDeviceId] is included in the payload so Android can match the event.
     */
    suspend fun sendLinkConfirmed(phoneUserId: String, pendingDeviceId: String) {
        val payload = """{"deviceId":"$pendingDeviceId","status":"confirmed"}""".toByteArray()
        relay.sendRawMessage(
            recipientId = phoneUserId,
            payload = payload,
            contentType = CONTENT_TYPE_LINK_CONFIRMED
        )
    }

    /**
     * GAP-06 / R-06: Desktop user denied the link.
     * Sends content_type=20 to Android so it can remove the pending device.
     */
    suspend fun sendLinkDenied(phoneUserId: String, pendingDeviceId: String) {
        val payload = """{"deviceId":"$pendingDeviceId","status":"denied"}""".toByteArray()
        relay.sendRawMessage(
            recipientId = phoneUserId,
            payload = payload,
            contentType = CONTENT_TYPE_LINK_DENIED
        )
    }

    /** Send a type-13 unlink notification to the given relay userId. */
    suspend fun sendUnlinkNotification(recipientUserId: String) {
        relay.sendRawMessage(
            recipientId = recipientUserId,
            payload = "unlink".toByteArray(),
            contentType = CONTENT_TYPE_DEVICE_UNLINK
        )
    }

    /** Phone notified us it has unlinked — clear local data. */
    private fun handleRemoteUnlink() {
        scope.launch {
            val devices = DeviceLinkManager.getApprovedDevices()
            for (device in devices) {
                // Unlink without sending another notification (notifyPeer = null)
                DeviceLinkManager.unlinkDevice(device.deviceId)
            }
        }
    }

    /**
     * Send a file to a phone contact by routing through the linked phone (content_type=17).
     * Capped at 10 MB.
     */
    suspend fun sendFile(file: java.io.File, recipientId: String): Result<DesktopMessage> =
        withContext(Dispatchers.IO) {
            val maxBytes = 10 * 1024 * 1024
            if (file.length() > maxBytes) {
                return@withContext Result.failure(IllegalArgumentException("File too large (max 10 MB)"))
            }
            val phone = DeviceLinkManager.getApprovedDevices().firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("No linked phone"))

            val fileBytes = file.readBytes()
            val fileDataB64 = java.util.Base64.getEncoder().encodeToString(fileBytes)
            val mimeType = runCatching {
                java.nio.file.Files.probeContentType(file.toPath())
            }.getOrNull() ?: "application/octet-stream"
            val escapedName = file.name.replace("\\", "\\\\").replace("\"", "\\\"")
            val escapedMime = mimeType.replace("\"", "\\\"")

            val payload = """{"fileName":"$escapedName","mimeType":"$escapedMime","fileData":"$fileDataB64","recipientId":"$recipientId","senderId":"${phone.phoneUserId}"}""".toByteArray()

            val msgId = generateUUID()
            val localMsg = MessageRepository.save(
                contactId = recipientId,
                content = "[File: ${file.name}]",
                isOutgoing = true,
                status = "sending",
                messageId = msgId
            )

            runCatching {
                relay.sendRawMessage(
                    recipientId = phone.phoneUserId,
                    payload = payload,
                    contentType = CONTENT_TYPE_DESKTOP_MEDIA_REQUEST
                )
                MessageRepository.updateStatus(msgId, "sent")
                localMsg.copy(status = "sent")
            }.also { result ->
                if (result.isFailure) MessageRepository.updateStatus(msgId, "failed")
            }
        }

    fun dispose() = scope.cancel()
}
