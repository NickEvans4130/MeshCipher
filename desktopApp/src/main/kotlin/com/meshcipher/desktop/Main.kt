package com.meshcipher.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.meshcipher.desktop.data.AppDatabase
import com.meshcipher.desktop.data.DeviceLinkManager
import com.meshcipher.desktop.data.MessagingManager
import com.meshcipher.desktop.data.SettingsRepository
import com.meshcipher.desktop.crypto.MessageCrypto
import com.meshcipher.desktop.network.RelayAuthManager
import com.meshcipher.desktop.network.RelayTransport
import com.meshcipher.desktop.network.TorConnectivityChecker
import com.meshcipher.desktop.ui.MeshCipherApp
import com.meshcipher.desktop.ui.MeshCipherTray
import com.meshcipher.desktop.ui.RelaySetupScreen
import com.meshcipher.shared.crypto.KeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun main() {
    // Single-instance lock — exit immediately if another instance is already running
    val lockFile = com.meshcipher.desktop.platform.DesktopPlatform.configDir.resolve(".lock")
    val lockChannel = java.nio.channels.FileChannel.open(
        lockFile.toPath(),
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.WRITE
    )
    val lock = lockChannel.tryLock()
    if (lock == null) {
        System.err.println("MeshCipher is already running.")
        return
    }
    Runtime.getRuntime().addShutdownHook(Thread { lock.release(); lockChannel.close() })

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

        // TOR warning dialog state
        var showTorWarning by remember { mutableStateOf(false) }

        // On startup, check TOR connectivity when enabled
        LaunchedEffect(Unit) {
            if (SettingsRepository.torEnabled.value) {
                withContext(Dispatchers.IO) {
                    val status = TorConnectivityChecker.check()
                    if (status == TorConnectivityChecker.TorStatus.NOT_RUNNING) {
                        showTorWarning = true
                    }
                }
            }
        }

        if (showTorWarning) {
            androidx.compose.ui.window.DialogWindow(
                onCloseRequest = { showTorWarning = false },
                title = "TOR Not Detected"
            ) {
                MaterialTheme {
                    AlertDialog(
                        onDismissRequest = { showTorWarning = false },
                        title = { Text("TOR Not Detected") },
                        text = {
                            Text(
                                "TOR is enabled but not detected on port 9050.\n\n" +
                                "Install and start TOR:\n" +
                                "  Fedora: sudo dnf install tor && sudo systemctl start tor\n" +
                                "  Debian: sudo apt install tor && sudo systemctl start tor\n\n" +
                                "Relay traffic will use a direct connection until TOR is available."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showTorWarning = false }) { Text("OK") }
                        }
                    )
                }
            }
        }

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
            relay = relay,
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
                MeshCipherApp(messagingManager, relay)
            }
        }
    }
}
