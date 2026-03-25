package com.meshcipher.data.tor

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R-12: Verifies that the Tor SOCKS proxy is fully bootstrapped before any P2P message is sent.
 *
 * Three checks are performed:
 * 1. Orbot is installed on the device (provides the system Tor daemon when EmbeddedTorManager is
 *    not used, and indicates the user has opted in to Tor-based transport).
 * 2. EmbeddedTorManager reports state == RUNNING and bootstrapPercent == 100, confirming the
 *    embedded daemon has completed its circuit-building handshake with the Tor network.
 * 3. The SOCKS port is actually bound and accepting connections — guards against a race where the
 *    state machine believes Tor is running but the port is not yet open.
 */
@Singleton
class TorBootstrapVerifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val embeddedTorManager: EmbeddedTorManager
) {
    sealed class VerifyResult {
        object Ready : VerifyResult()
        data class NotReady(val reason: String) : VerifyResult()
    }

    fun verify(): VerifyResult {
        // 1. Orbot presence check — optional but logged for diagnostics.
        val orbotInstalled = isOrbotInstalled()
        Timber.d("TorBootstrapVerifier: orbotInstalled=%b", orbotInstalled)

        // 2. EmbeddedTorManager state check.
        val status = embeddedTorManager.status.value
        if (status.state != EmbeddedTorManager.State.RUNNING) {
            return VerifyResult.NotReady(
                "Tor is not running (state=${status.state}). Wait for Tor to bootstrap before sending."
            )
        }
        if (status.bootstrapPercent < 100) {
            return VerifyResult.NotReady(
                "Tor bootstrap incomplete (${status.bootstrapPercent}%). " +
                "Wait for 100% before sending."
            )
        }

        // 3. SOCKS port liveness check.
        val socksPort = if (status.socksPort > 0) status.socksPort else DEFAULT_SOCKS_PORT
        if (!isSocksPortBound(socksPort)) {
            return VerifyResult.NotReady(
                "Tor SOCKS port $socksPort is not accepting connections. " +
                "The daemon may still be initialising."
            )
        }

        return VerifyResult.Ready
    }

    private fun isOrbotInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(ORBOT_PACKAGE, PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isSocksPortBound(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(SOCKS_HOST, port), PORT_CHECK_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            Timber.w("TorBootstrapVerifier: SOCKS port %d not reachable: %s", port, e.message)
            false
        }
    }

    companion object {
        private const val ORBOT_PACKAGE = "org.torproject.android"
        private const val SOCKS_HOST = "127.0.0.1"
        private const val DEFAULT_SOCKS_PORT = 9050
        private const val PORT_CHECK_TIMEOUT_MS = 1_000
    }
}
