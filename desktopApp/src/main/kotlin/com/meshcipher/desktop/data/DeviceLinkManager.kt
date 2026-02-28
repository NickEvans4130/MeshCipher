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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.awt.image.BufferedImage
import java.util.Base64

object DeviceLinkManager {

    private val keyManager = KeyManager()
    private val deviceId: String = loadOrCreateDeviceId()

    val localDeviceId: String get() = deviceId

    fun getPublicKeyHex(): String {
        val pubKey = if (keyManager.hasHardwareKey()) {
            keyManager.getPublicKey()
        } else {
            keyManager.generateHardwareKey()
        }
        return pubKey.toHex()
    }

    fun buildLinkRequest(): DeviceLinkRequest = DeviceLinkRequest(
        deviceId = deviceId,
        deviceName = resolveHostname(),
        deviceType = DeviceType.DESKTOP,
        publicKeyHex = getPublicKeyHex(),
        timestamp = System.currentTimeMillis()
    )

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
            }
        }
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
