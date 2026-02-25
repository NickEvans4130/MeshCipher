package com.meshcipher.desktop.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class TorState { STOPPED, STARTING, BOOTSTRAPPED, FAILED }

/**
 * Manages the system `tor` daemon for TOR-routed relay transport.
 *
 * Requires `tor` to be installed on the system:
 *   Fedora/RHEL:  sudo dnf install tor
 *   Debian/Ubuntu: sudo apt install tor
 *
 * The daemon is started as a child process with a dedicated data directory
 * and SOCKS port under `~/.local/share/meshcipher/tor/`.
 *
 * Use [socksPort] to configure Ktor's proxy, or set system SOCKS properties.
 */
class TorManager(torDataRoot: File) {

    val torDataDir: File = File(torDataRoot, "tor").also { it.mkdirs() }
    val socksPort: Int = 19050 // non-default port to avoid conflicts

    private val _state = MutableStateFlow(TorState.STOPPED)
    val state: StateFlow<TorState> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value == TorState.BOOTSTRAPPED

    private var torProcess: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Start the TOR daemon. Waits up to 90 seconds for bootstrap.
     * Throws if `tor` is not installed or bootstrap times out.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        check(isTorInstalled()) {
            "TOR is not installed. Install with: sudo dnf install tor"
        }

        _state.value = TorState.STARTING

        val torrcFile = writeTorrc()

        val pb = ProcessBuilder("tor", "-f", torrcFile.absolutePath)
        pb.redirectErrorStream(true)
        val process = pb.start()
        torProcess = process

        scope.launch { monitorOutput(process) }

        // Wait for bootstrap (90 s timeout)
        withTimeoutOrNull(90_000L) {
            while (_state.value == TorState.STARTING) delay(500)
        } ?: run {
            process.destroy()
            torProcess = null
            _state.value = TorState.FAILED
            error("TOR bootstrap timed out after 90 seconds")
        }

        if (_state.value != TorState.BOOTSTRAPPED) {
            error("TOR failed to start — check tor logs")
        }
    }

    /** Stop the TOR daemon. */
    fun stop() {
        torProcess?.destroy()
        torProcess = null
        _state.value = TorState.STOPPED
    }

    /**
     * Apply TOR SOCKS proxy via Java system properties.
     * This routes all new TCP connections through the TOR SOCKS5 proxy.
     * Call before [RelayTransport.connect].
     */
    fun applySystemProxy() {
        check(isRunning) { "TOR is not running" }
        System.setProperty("socksProxyHost", "127.0.0.1")
        System.setProperty("socksProxyPort", socksPort.toString())
        System.setProperty("socksNonProxyHosts", "")
    }

    /** Remove SOCKS system properties (revert to direct connection). */
    fun clearSystemProxy() {
        System.clearProperty("socksProxyHost")
        System.clearProperty("socksProxyPort")
        System.clearProperty("socksNonProxyHosts")
    }

    /** Returns true if the `tor` binary is on PATH. */
    fun isTorInstalled(): Boolean = try {
        val result = ProcessBuilder("which", "tor").start().waitFor()
        result == 0
    } catch (_: Exception) { false }

    private fun writeTorrc(): File {
        val torrc = File(torDataDir, "torrc")
        torrc.writeText(
            """
            SocksPort $socksPort
            ControlPort 0
            DataDirectory ${torDataDir.absolutePath}
            Log notice stdout
            """.trimIndent()
        )
        return torrc
    }

    private suspend fun monitorOutput(process: Process) {
        process.inputStream.bufferedReader().use { reader ->
            for (line in reader.lineSequence()) {
                if (line.contains("Bootstrapped 100%") || line.contains("Done")) {
                    _state.value = TorState.BOOTSTRAPPED
                }
                if (line.contains("Problem bootstrapping") || line.contains("[err]")) {
                    _state.value = TorState.FAILED
                }
            }
        }
        // Process exited
        if (_state.value == TorState.BOOTSTRAPPED || _state.value == TorState.STARTING) {
            _state.value = TorState.STOPPED
        }
    }
}
