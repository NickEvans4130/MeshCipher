package com.meshcipher.data.tor

import com.meshcipher.domain.model.P2PMessage
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class P2PClient @Inject constructor(
    private val embeddedTorManager: EmbeddedTorManager
) {

    private val connectionPool = ConcurrentHashMap<String, Socket>()

    fun sendMessage(onionAddress: String, message: P2PMessage): Result<P2PMessage?> {
        return try {
            val socket = getOrCreateConnection(onionAddress)
            message.writeToStream(socket.getOutputStream())

            // Read ACK if this is a TEXT message
            val ack = if (message.type == P2PMessage.Type.TEXT) {
                try {
                    P2PMessage.readFromStream(socket.getInputStream())
                } catch (e: Exception) {
                    Timber.w(e, "Failed to read ACK from %s", onionAddress)
                    null
                }
            } else {
                null
            }

            Timber.d("Sent P2P message %s to %s", message.messageId, onionAddress)
            Result.success(ack)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send P2P message to %s", onionAddress)
            // Remove stale connection
            connectionPool.remove(onionAddress)?.let { socket ->
                try { socket.close() } catch (_: Exception) {}
            }
            Result.failure(e)
        }
    }

    private fun getOrCreateConnection(onionAddress: String): Socket {
        val existing = connectionPool[onionAddress]
        if (existing != null && !existing.isClosed && existing.isConnected) {
            return existing
        }

        // Clean up stale entry
        connectionPool.remove(onionAddress)?.let { socket ->
            try { socket.close() } catch (_: Exception) {}
        }

        val proxy = embeddedTorManager.getSocksProxy()
        val socket = Socket(proxy)

        // Use createUnresolved so Tor resolves the .onion address
        val address = InetSocketAddress.createUnresolved(onionAddress, 80)
        socket.connect(address, CONNECT_TIMEOUT_MS)

        connectionPool[onionAddress] = socket
        Timber.d("Established connection to %s via Tor SOCKS proxy", onionAddress)

        return socket
    }

    fun disconnect(onionAddress: String) {
        connectionPool.remove(onionAddress)?.let { socket ->
            try {
                socket.close()
                Timber.d("Disconnected from %s", onionAddress)
            } catch (e: Exception) {
                Timber.w(e, "Error disconnecting from %s", onionAddress)
            }
        }
    }

    fun disconnectAll() {
        connectionPool.keys().toList().forEach { address ->
            disconnect(address)
        }
        Timber.d("Disconnected from all peers")
    }

    fun isConnected(onionAddress: String): Boolean {
        val socket = connectionPool[onionAddress] ?: return false
        return !socket.isClosed && socket.isConnected
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 120_000 // 120s for Tor circuit building
    }
}
