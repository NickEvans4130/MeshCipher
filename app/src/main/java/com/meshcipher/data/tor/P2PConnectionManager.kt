package com.meshcipher.data.tor

import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.domain.model.P2PMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class P2PConnectionManager @Inject constructor(
    private val embeddedTorManager: EmbeddedTorManager,
    private val hiddenServiceServer: HiddenServiceServer,
    private val p2pClient: P2PClient,
    private val appPreferences: AppPreferences
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val torStatus: StateFlow<EmbeddedTorManager.EmbeddedTorStatus> = embeddedTorManager.status
    val incomingMessages: SharedFlow<P2PMessage> = hiddenServiceServer.incomingMessages

    fun startP2P() {
        scope.launch {
            Timber.d("Starting P2P Tor...")

            // Start the hidden service server first (gets a local port)
            val localPort = hiddenServiceServer.start()
            Timber.d("Hidden service server started on port %d", localPort)

            // Start embedded Tor
            embeddedTorManager.start()

            // Wait for Tor to bootstrap (bootstrapPercent >= 100)
            var waited = 0
            while (waited < 180) {
                val status = embeddedTorManager.status.value
                if (status.state == EmbeddedTorManager.State.ERROR) {
                    Timber.e("P2P Tor failed during bootstrap: %s", status.errorMessage)
                    return@launch
                }
                if (status.bootstrapPercent >= 100) {
                    break
                }
                delay(1000)
                waited++
            }

            if (embeddedTorManager.status.value.bootstrapPercent < 100) {
                Timber.e("P2P Tor bootstrap timed out after %d seconds", waited)
                return@launch
            }

            Timber.d("Tor bootstrapped, creating hidden service on port %d", localPort)

            // Create hidden service directly (suspend, not fire-and-forget)
            embeddedTorManager.createHiddenService(localPort)

            val status = embeddedTorManager.status.value
            if (status.state == EmbeddedTorManager.State.RUNNING && status.onionAddress != null) {
                appPreferences.setOnionAddress(status.onionAddress)
                Timber.d("P2P Tor fully started. Onion: %s", status.onionAddress)
            } else {
                Timber.e("P2P Tor failed to start. State: %s, Error: %s",
                    status.state, status.errorMessage)
            }
        }
    }

    fun stopP2P() {
        scope.launch {
            Timber.d("Stopping P2P Tor...")
            p2pClient.disconnectAll()
            hiddenServiceServer.stop()
            embeddedTorManager.stop()
            Timber.d("P2P Tor stopped")
        }
    }

    suspend fun sendMessage(onionAddress: String, message: P2PMessage): Result<P2PMessage?> {
        return withContext(Dispatchers.IO) {
            p2pClient.sendMessage(onionAddress, message)
        }
    }

    fun isRunning(): Boolean = embeddedTorManager.isRunning()

    fun getOnionAddress(): String? = embeddedTorManager.getOnionAddress()
}
