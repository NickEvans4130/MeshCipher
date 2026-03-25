package com.meshcipher.desktop.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.meshcipher.shared.crypto.KeyManager
import com.meshcipher.shared.domain.model.DeviceType
import com.meshcipher.shared.util.generateUUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.awt.image.BufferedImage
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class LinkedPhone(
    val deviceId: String,
    val deviceName: String,
    val linkedAt: Long,
    val phoneUserId: String = "",
    val phonePublicKeyHex: String = ""
)

object DeviceLinkManager {

    private val keyManager = KeyManager()
    private val deviceId: String = loadOrCreateDeviceId()

    private val _deviceLinked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deviceLinked: SharedFlow<Unit> = _deviceLinked.asSharedFlow()

    private val _dataCleared = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val dataCleared: SharedFlow<Unit> = _dataCleared.asSharedFlow()

    val localDeviceId: String get() = deviceId

    // GAP-05 / R-06: Track the nonce for the current QR session so the confirmation
    // handshake (RM-04) can tie the confirmation back to this specific enrolment.
    @Volatile private var _currentNonce: String = ""
    val currentNonce: String get() = _currentNonce

    fun getPublicKeyHex(): String {
        val pubKey = if (keyManager.hasHardwareKey()) {
            keyManager.getPublicKey()
        } else {
            keyManager.generateHardwareKey()
        }
        return pubKey.toHex()
    }

    /**
     * Generates a fresh QR session nonce (GAP-05 / R-06).
     * Each call produces a new 32-byte cryptographically random value, invalidating
     * any previously displayed QR code.
     */
    private fun generateNonce(): String {
        val nonceBytes = ByteArray(32)
        SecureRandom().nextBytes(nonceBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
    }

    fun buildLinkRequest(): DeviceLinkRequest {
        // Generate a fresh nonce for every new QR session (GAP-05 / R-06)
        _currentNonce = generateNonce()
        return DeviceLinkRequest(
            deviceId = deviceId,
            deviceName = resolveHostname(),
            deviceType = DeviceType.DESKTOP,
            publicKeyHex = getPublicKeyHex(),
            timestamp = System.currentTimeMillis(),
            nonce = _currentNonce
        )
    }

    /**
     * Generates a QR code image encoding the link request as:
     *   meshcipher://link/<base64url-encoded-JSON>
     *
     * The Android QR scanner reads this URI scheme and extracts the payload.
     */
    suspend fun generateQrImage(sizePx: Int = 400): BufferedImage = withContext(Dispatchers.IO) {
        val request = buildLinkRequest()
        val json = Json.encodeToString(request)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val uri = "meshcipher://link/$encoded"
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = MultiFormatWriter().encode(uri, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        MatrixToImageWriter.toBufferedImage(matrix)
    }

    suspend fun processLinkResponse(response: DeviceLinkResponse) = withContext(Dispatchers.IO) {
        val pubKeyHex = response.phonePublicKeyHex
        if (!response.approved || pubKeyHex == null) return@withContext
        transaction {
            DeviceLinksTable.insert {
                it[DeviceLinksTable.deviceId] = response.phoneDeviceId
                it[DeviceLinksTable.deviceName] = "Linked Phone"
                it[DeviceLinksTable.publicKeyHex] = pubKeyHex
                it[DeviceLinksTable.linkedAt] = System.currentTimeMillis()
                it[DeviceLinksTable.approved] = true
                it[DeviceLinksTable.phoneUserId] = response.phoneUserId
            }
        }
        _deviceLinked.emit(Unit)
    }

    /**
     * Derives the desktop's userId from its public key.
     * Algorithm matches Android IdentityManager: Base64Url(SHA-256(publicKey)).take(32)
     */
    fun getDesktopUserId(): String {
        val pubKey = if (keyManager.hasHardwareKey()) keyManager.getPublicKey()
                     else keyManager.generateHardwareKey()
        val hash = MessageDigest.getInstance("SHA-256").digest(pubKey)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).take(32)
    }

    /**
     * Returns all approved linked devices (phones that have approved this desktop).
     */
    suspend fun getApprovedDevices(): List<LinkedPhone> = withContext(Dispatchers.IO) {
        transaction {
            DeviceLinksTable.select { DeviceLinksTable.approved eq true }
                .map { row ->
                    LinkedPhone(
                        deviceId = row[DeviceLinksTable.deviceId],
                        deviceName = row[DeviceLinksTable.deviceName],
                        linkedAt = row[DeviceLinksTable.linkedAt],
                        phoneUserId = row[DeviceLinksTable.phoneUserId],
                        phonePublicKeyHex = row[DeviceLinksTable.publicKeyHex]
                    )
                }
        }
    }

    /**
     * Removes a linked device by its deviceId.
     */
    suspend fun unlinkDevice(
        linkedDeviceId: String,
        notifyPeer: (suspend (phoneUserId: String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        // Notify the phone before removing the record so we still have its userId
        val phoneUserId = transaction {
            DeviceLinksTable.select { DeviceLinksTable.deviceId eq linkedDeviceId }
                .firstOrNull()?.get(DeviceLinksTable.phoneUserId)
        }
        if (notifyPeer != null && !phoneUserId.isNullOrBlank()) {
            runCatching { notifyPeer(phoneUserId) }
        }
        transaction {
            DeviceLinksTable.deleteWhere { DeviceLinksTable.deviceId eq linkedDeviceId }
        }
        // Clear all messages and contacts so a different account can link cleanly
        MessageRepository.clearAll()
        ContactRepository.clearAll()
        _dataCleared.emit(Unit)
    }

    /**
     * Generates a QR code that other MeshCipher users can scan to add this desktop
     * as a contact. Encodes as:
     *   meshcipher://add?userId=<id>&publicKey=<base64>&deviceType=DESKTOP
     */
    /**
     * Generates a contact QR. If the phone is linked, encodes the phone's identity so
     * contacts scan to add the user's real account. Falls back to desktop identity if not linked.
     */
    suspend fun generateContactQrImage(sizePx: Int = 300, linkedPhone: LinkedPhone? = null): BufferedImage = withContext(Dispatchers.IO) {
        val uri = if (linkedPhone != null && linkedPhone.phoneUserId.isNotBlank() && linkedPhone.phonePublicKeyHex.isNotBlank()) {
            val pubKeyBytes = linkedPhone.phonePublicKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val pubKeyB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(pubKeyBytes)
            "meshcipher://add?userId=${linkedPhone.phoneUserId}&publicKey=$pubKeyB64&deviceType=ANDROID"
        } else {
            val userId = getDesktopUserId()
            val pubKey = if (keyManager.hasHardwareKey()) keyManager.getPublicKey()
                         else keyManager.generateHardwareKey()
            val pubKeyB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(pubKey)
            "meshcipher://add?userId=$userId&publicKey=$pubKeyB64&deviceType=DESKTOP"
        }
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = MultiFormatWriter().encode(uri, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        MatrixToImageWriter.toBufferedImage(matrix)
    }

    private fun loadOrCreateDeviceId(): String {
        val idFile = com.meshcipher.desktop.platform.DesktopPlatform.configDir
            .resolve("device.id")
        if (idFile.exists()) return idFile.readText().trim()
        val id = generateUUID()
        idFile.writeText(id)
        return id
    }

    private fun resolveHostname(): String =
        try { java.net.InetAddress.getLocalHost().hostName } catch (e: Exception) { "Desktop" }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
