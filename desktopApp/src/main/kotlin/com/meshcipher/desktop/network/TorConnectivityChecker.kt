package com.meshcipher.desktop.network

/**
 * Checks whether a TOR SOCKS5 proxy is reachable on localhost:9050.
 *
 * Usage: call [check] on app startup when tor_enabled=true; surface
 * the result to the user before attempting a relay connection.
 */
object TorConnectivityChecker {

    private const val TOR_HOST = "127.0.0.1"
    private const val TOR_PORT = 9050
    private const val TIMEOUT_MS = 2_000

    enum class TorStatus { RUNNING, NOT_RUNNING, UNKNOWN }

    /**
     * Attempts a plain TCP connection to localhost:9050.
     * Returns [TorStatus.RUNNING] if the port accepts connections,
     * [TorStatus.NOT_RUNNING] if the connection is refused,
     * or [TorStatus.UNKNOWN] if the check itself fails unexpectedly.
     */
    fun check(): TorStatus = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(TOR_HOST, TOR_PORT), TIMEOUT_MS)
            TorStatus.RUNNING
        }
    } catch (e: java.net.ConnectException) {
        TorStatus.NOT_RUNNING
    } catch (_: Exception) {
        TorStatus.UNKNOWN
    }
}
