package com.meshcipher.desktop.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.ServerSocket

/**
 * Manages a TOR hidden service (.onion) for P2P TOR mode.
 *
 * When enabled, the desktop runs a local TCP server and exposes it as a
 * `.onion` address via TOR's HiddenService configuration. Remote peers
 * can connect to this `.onion` address directly through TOR — no relay
 * server required, no relay metadata.
 *
 * Prerequisites:
 * - [TorManager] must be started first
 * - TOR must be reloaded (SIGHUP) after adding the hidden service config
 *
 * Usage:
 * 1. Create and start [TorManager]
 * 2. Create [HiddenServiceManager]
 * 3. Call [setup] — updates torrc and sends SIGHUP to reload TOR
 * 4. Call [startListener] — starts accepting P2P connections
 * 5. Share [onionAddress] with contacts out-of-band
 */
class HiddenServiceManager(
    private val torManager: TorManager,
    /** Local port that TOR forwards to from the .onion address. */
    val servicePort: Int = 18080
) {
    private val hiddenServiceDir = File(torManager.torDataDir, "hidden_service")

    private val _onionAddress = MutableStateFlow<String?>(null)
    val onionAddress: StateFlow<String?> = _onionAddress.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null

    /**
     * Configure TOR to create a hidden service pointing to [servicePort].
     * Returns the `.onion` address once TOR has generated it (may take ~30 s).
     * Call [TorManager.start] first.
     */
    suspend fun setup(): String = withContext(Dispatchers.IO) {
        check(torManager.isRunning) { "TorManager must be started before setting up hidden service" }

        hiddenServiceDir.mkdirs()

        // Append HiddenService lines to torrc
        val torrc = File(torManager.torDataDir, "torrc")
        val existing = torrc.readText()
        val hsConfig = """

HiddenServiceDir ${hiddenServiceDir.absolutePath}
HiddenServicePort 80 127.0.0.1:$servicePort
"""
        if (!existing.contains("HiddenServiceDir")) {
            torrc.appendText(hsConfig)

            // Send SIGHUP to tor process to reload config (Linux only)
            sendSighup()
        }

        // Wait for hostname file (up to 60 s)
        val hostnameFile = File(hiddenServiceDir, "hostname")
        withTimeoutOrNull(60_000L) {
            while (!hostnameFile.exists()) delay(1_000)
        } ?: error("Hidden service hostname not generated within 60 seconds")

        val address = hostnameFile.readText().trim()
        _onionAddress.value = address
        address
    }

    /**
     * Start accepting P2P connections on [servicePort].
     * Incoming connections are handled by [onConnection].
     */
    fun startListener(onConnection: suspend (connection: java.net.Socket) -> Unit) {
        val ss = ServerSocket(servicePort).also { serverSocket = it }
        scope.launch {
            while (ss.isBound && !ss.isClosed) {
                runCatching {
                    val socket = withContext(Dispatchers.IO) { ss.accept() }
                    launch { onConnection(socket) }
                }
            }
        }
    }

    /** Stop the local listener. Does not remove the hidden service from TOR. */
    fun stopListener() {
        serverSocket?.close()
        serverSocket = null
        scope.cancel()
    }

    private fun sendSighup() {
        try {
            // Find tor PID via pgrep and send SIGHUP to reload config
            val pidProcess = ProcessBuilder("pgrep", "-x", "tor").start()
            val pid = pidProcess.inputStream.bufferedReader().readLine()?.trim() ?: return
            ProcessBuilder("kill", "-HUP", pid).start().waitFor()
        } catch (_: Exception) {
            // SIGHUP not critical — TOR will apply changes on next restart
        }
    }
}
