package com.meshcipher.data.tor

import com.meshcipher.domain.model.P2PMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HiddenServiceServer @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incomingMessages = MutableSharedFlow<P2PMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<P2PMessage> = _incomingMessages.asSharedFlow()

    private val connectedPeers = ConcurrentHashMap<String, Socket>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    val port: Int get() = serverSocket?.localPort ?: 0

    fun start(): Int {
        if (running) {
            Timber.d("HiddenServiceServer already running on port %d", port)
            return port
        }

        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        running = true

        Timber.d("HiddenServiceServer started on port %d", ss.localPort)

        scope.launch {
            acceptLoop(ss)
        }

        return ss.localPort
    }

    fun stop() {
        running = false
        connectedPeers.values.forEach { socket ->
            try {
                socket.close()
            } catch (e: Exception) {
                Timber.w(e, "Error closing peer socket")
            }
        }
        connectedPeers.clear()

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing server socket")
        }
        serverSocket = null

        Timber.d("HiddenServiceServer stopped")
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (running && scope.isActive) {
            try {
                val client = ss.accept()
                val peerId = UUID.randomUUID().toString()
                connectedPeers[peerId] = client
                Timber.d("Accepted peer connection: %s", peerId)

                scope.launch {
                    handleClient(peerId, client)
                }
            } catch (e: Exception) {
                if (running) {
                    Timber.e(e, "Error accepting connection")
                }
            }
        }
    }

    private suspend fun handleClient(peerId: String, socket: Socket) {
        try {
            val input = socket.getInputStream()

            while (running && !socket.isClosed && scope.isActive) {
                val message = P2PMessage.readFromStream(input)
                Timber.d("Received P2P message from %s: type=%s id=%s",
                    peerId, message.type, message.messageId)

                _incomingMessages.emit(message)

                // Send ACK for TEXT messages
                if (message.type == P2PMessage.Type.TEXT) {
                    try {
                        val ack = P2PMessage(
                            type = P2PMessage.Type.ACK,
                            messageId = message.messageId,
                            senderId = message.recipientId,
                            recipientId = message.senderId,
                            timestamp = System.currentTimeMillis()
                        )
                        ack.writeToStream(socket.getOutputStream())
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to send ACK for message %s", message.messageId)
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                Timber.d("Peer %s disconnected: %s", peerId, e.message)
            }
        } finally {
            connectedPeers.remove(peerId)
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
