package com.meshcipher.data.tor

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class TorStatus(
        val orbotInstalled: Boolean = false,
        val orbotRunning: Boolean = false,
        val proxyReady: Boolean = false
    )

    private val _status = MutableStateFlow(TorStatus())
    val status: StateFlow<TorStatus> = _status.asStateFlow()

    fun isOrbotInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isOrbotRunning(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(SOCKS_HOST, SOCKS_PORT), PROBE_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun refreshStatus() {
        val installed = isOrbotInstalled()
        val running = if (installed) isOrbotRunning() else false
        val newStatus = TorStatus(
            orbotInstalled = installed,
            orbotRunning = running,
            proxyReady = running
        )
        _status.value = newStatus
        Timber.d("TOR status: installed=%b, running=%b", installed, running)
    }

    fun createTorProxy(): Proxy {
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress(SOCKS_HOST, SOCKS_PORT))
    }

    fun getOrbotInstallIntent(): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(ORBOT_PLAY_STORE_URL))
    }

    fun getOrbotLaunchIntent(): Intent? {
        return context.packageManager.getLaunchIntentForPackage(ORBOT_PACKAGE)
    }

    companion object {
        const val ORBOT_PACKAGE = "org.torproject.android"
        const val SOCKS_HOST = "127.0.0.1"
        const val SOCKS_PORT = 9050
        private const val PROBE_TIMEOUT_MS = 2000
        private const val ORBOT_PLAY_STORE_URL =
            "https://play.google.com/store/apps/details?id=org.torproject.android"
    }
}
