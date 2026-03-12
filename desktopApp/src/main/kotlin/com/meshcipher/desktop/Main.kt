package com.meshcipher.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.meshcipher.desktop.data.AppDatabase
import com.meshcipher.desktop.data.DeviceLinkManager
import com.meshcipher.desktop.data.MessagingManager
import com.meshcipher.desktop.crypto.MessageCrypto
import com.meshcipher.desktop.network.RelayAuthManager
import com.meshcipher.desktop.network.RelayTransport
import com.meshcipher.desktop.ui.MeshCipherApp
import com.meshcipher.desktop.ui.MeshCipherTray
import com.meshcipher.desktop.ui.RelaySetupScreen
import com.meshcipher.shared.crypto.KeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun main() {
    AppDatabase.init()

    val keyManager = KeyManager()
    val localDeviceId = DeviceLinkManager.localDeviceId

    application {
        val trayState = rememberTrayState()
        var isVisible by remember { mutableStateOf(true) }

        // Whether the relay is configured and authenticated
        var setupComplete by remember { mutableStateOf(RelayAuthManager.hasValidConfig()) }

        // Relay + messaging — initialised once setup is confirmed
        var relay by remember { mutableStateOf<RelayTransport?>(null) }
        var messagingManager by remember { mutableStateOf<MessagingManager?>(null) }

        // When setup becomes complete (first run or after setup screen), init relay
        LaunchedEffect(setupComplete) {
            if (!setupComplete) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                val config = RelayAuthManager.loadConfig()
                val relayUrl = config["relayUrl"]
                val authToken = config["authToken"]
                if (!relayUrl.isNullOrBlank() && !authToken.isNullOrBlank()) {
                    val localUserId = DeviceLinkManager.getDesktopUserId()
                    val r = RelayTransport(relayUrl, localDeviceId, authToken, localUserId).also { it.connect() }
                    val crypto = MessageCrypto(localUserId, keyManager)
                    relay = r
                    messagingManager = MessagingManager(localDeviceId, crypto, r)
                }
            }
        }

        MeshCipherTray(
            trayState = trayState,
            onShow = { isVisible = true },
            onQuit = {
                messagingManager?.dispose()
                relay?.disconnect()
                exitApplication()
            }
        )

        Window(
            onCloseRequest = { isVisible = false },
            visible = isVisible,
            title = "MeshCipher",
            state = rememberWindowState()
        ) {
            if (!setupComplete) {
                RelaySetupScreen(
                    keyManager = keyManager,
                    deviceId = localDeviceId,
                    onSetupComplete = { setupComplete = true }
                )
            } else {
                MeshCipherApp(messagingManager)
            }
        }
    }
}
