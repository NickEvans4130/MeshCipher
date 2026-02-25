package com.meshcipher.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.meshcipher.desktop.data.AppDatabase
import com.meshcipher.desktop.data.DeviceLinkManager
import com.meshcipher.desktop.data.MessageRepository
import com.meshcipher.desktop.data.MessagingManager
import com.meshcipher.desktop.crypto.MessageCrypto
import com.meshcipher.desktop.network.RelayTransport
import com.meshcipher.desktop.platform.DesktopPlatform
import com.meshcipher.desktop.ui.MeshCipherApp
import com.meshcipher.shared.crypto.KeyManager
import java.util.Properties

fun main() {
    AppDatabase.init()

    val keyManager = KeyManager()
    val localDeviceId = DeviceLinkManager.localDeviceId

    // Load relay configuration from ~/.config/meshcipher/relay.conf
    // Format: relayUrl=https://relay.meshcipher.com
    //         authToken=<JWT>
    val relayConfig = loadRelayConfig()
    val relayUrl = relayConfig["relayUrl"] ?: System.getenv("MESHCIPHER_RELAY_URL")
    val authToken = relayConfig["authToken"] ?: System.getenv("MESHCIPHER_AUTH_TOKEN")

    val relay = if (!relayUrl.isNullOrBlank() && !authToken.isNullOrBlank()) {
        RelayTransport(relayUrl, localDeviceId, authToken).also { it.connect() }
    } else {
        null
    }

    val messagingManager = if (relay != null) {
        val crypto = MessageCrypto(localDeviceId, keyManager)
        MessagingManager(localDeviceId, crypto, relay)
    } else {
        null
    }

    application {
        Window(
            onCloseRequest = {
                messagingManager?.dispose()
                relay?.disconnect()
                exitApplication()
            },
            title = "MeshCipher"
        ) {
            MeshCipherApp(messagingManager)
        }
    }
}

private fun loadRelayConfig(): Map<String, String> {
    val configFile = DesktopPlatform.configDir.resolve("relay.conf")
    if (!configFile.exists()) return emptyMap()
    return try {
        val props = Properties()
        configFile.inputStream().use { props.load(it) }
        props.entries.associate { (k, v) -> k.toString() to v.toString() }
    } catch (_: Exception) {
        emptyMap()
    }
}
