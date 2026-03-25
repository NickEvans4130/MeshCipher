package com.meshcipher.data.wifidirect

import com.meshcipher.domain.model.WifiDirectMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages TCP connections for WiFi Direct peer-to-peer messaging.
 *
 * GAP-07 / R-07: All serialisation uses [WifiDirectMessageCodec] (a typed binary
 * protocol). Java ObjectInputStream/ObjectOutputStream are not used anywhere in this
 * class; the RCE-via-deserialization gadget-chain attack surface is eliminated.
 *
 * A raw Java serialisation stream sent by a malicious peer (magic bytes 0xACED) will
 * produce an unknown type byte (0xAC = 172), which [WifiDirectMessageCodec.decode] rejects
 * with [IllegalArgumentException] without reading the rest of the stream.
 */
@Singleton
class WifiDirectSocketManager @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    private val clientSockets = ConcurrentHashMap<String, Socket>()
    // Buffered output streams keyed by peer address — access must be synchronised per entry.
    private val clientOutputStreams = ConcurrentHashMap<String, BufferedOutputStream>()

    private val _receivedMessages = MutableSharedFlow<WifiDirectMessage>(replay = 0)
    val receivedMessages: SharedFlow<WifiDirectMessage> = _receivedMessages.asSharedFlow()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _connectedClients = MutableStateFlow<Set<String>>(emptySet())
    val connectedClients: StateFlow<Set<String>> = _connectedClients.asStateFlow()

    fun startServer() {
        if (serverJob?.isActive == true) {
            Timber.d("Server already running")
            return
        }

        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(PORT).apply {
                    reuseAddress = true
                    soTimeout = 0 // No timeout, wait indefinitely
                }
                _isServerRunning.value = true
                Timber.d("Server started on port $PORT")

                while (isActive && serverSocket != null) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        val clientAddress = clientSocket.inetAddress.hostAddress ?: "unknown"
                        Timber.d("Client connected")

                        handleClient(clientSocket, clientAddress)
                    } catch (e: IOException) {
                        if (isActive) {
                            Timber.e(e, "Error accepting client")
                        }
                    }
                }
            } catch (e: IOException) {
                Timber.e(e, "Failed to start server")
            } finally {
                _isServerRunning.value = false
            }
        }
    }

    fun stopServer() {
        serverJob?.cancel()
        serverJob = null

        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Timber.e(e, "Error closing server socket")
        }
        serverSocket = null

        clientSockets.forEach { (_, socket) ->
            try {
                socket.close()
            } catch (e: IOException) {
                Timber.e(e, "Error closing client socket")
            }
        }
        clientSockets.clear()
        clientOutputStreams.clear()
        _connectedClients.value = emptySet()
        _isServerRunning.value = false

        Timber.d("Server stopped")
    }

    private fun handleClient(socket: Socket, clientAddress: String) {
        clientSockets[clientAddress] = socket
        _connectedClients.value = clientSockets.keys.toSet()

        scope.launch {
            try {
                val outputStream = BufferedOutputStream(socket.getOutputStream())
                clientOutputStreams[clientAddress] = outputStream

                val inputStream = BufferedInputStream(socket.getInputStream())

                while (isActive && !socket.isClosed) {
                    try {
                        val message = WifiDirectMessageCodec.decode(inputStream)
                        Timber.d("Received message: ${message::class.simpleName}")
                        _receivedMessages.emit(message)
                    } catch (e: IllegalArgumentException) {
                        // GAP-07 / R-07: Unknown type byte or oversized payload — log and drop.
                        // This covers Java-serialization-stream headers (0xACED) and any other
                        // unrecognised framing.
                        Timber.e(e, "Rejected malformed WiFi Direct message")
                        break
                    } catch (e: IOException) {
                        if (isActive) {
                            Timber.d("Client disconnected")
                        }
                        break
                    }
                }
            } catch (e: IOException) {
                Timber.e(e, "Error handling client")
            } finally {
                clientSockets.remove(clientAddress)
                clientOutputStreams.remove(clientAddress)
                _connectedClients.value = clientSockets.keys.toSet()
                try {
                    socket.close()
                } catch (e: IOException) {
                    // Ignore
                }
            }
        }
    }

    suspend fun connectToServer(serverAddress: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(serverAddress, PORT), CONNECTION_TIMEOUT)

            clientSockets[serverAddress] = socket
            _connectedClients.value = clientSockets.keys.toSet()

            val outputStream = BufferedOutputStream(socket.getOutputStream())
            clientOutputStreams[serverAddress] = outputStream

            // Start receiving messages from server
            scope.launch {
                try {
                    val inputStream = BufferedInputStream(socket.getInputStream())

                    while (isActive && !socket.isClosed) {
                        try {
                            val message = WifiDirectMessageCodec.decode(inputStream)
                            Timber.d("Received message from server: ${message::class.simpleName}")
                            _receivedMessages.emit(message)
                        } catch (e: IllegalArgumentException) {
                            // GAP-07 / R-07: Reject unknown/oversized messages (see handleClient).
                            Timber.e(e, "Rejected malformed message from WiFi Direct server")
                            break
                        } catch (e: IOException) {
                            if (isActive) {
                                Timber.d("Disconnected from server")
                            }
                            break
                        }
                    }
                } catch (e: IOException) {
                    Timber.e(e, "Error receiving from server")
                } finally {
                    clientSockets.remove(serverAddress)
                    clientOutputStreams.remove(serverAddress)
                    _connectedClients.value = clientSockets.keys.toSet()
                }
            }

            Timber.d("Connected to server")
            true
        } catch (e: IOException) {
            Timber.e(e, "Failed to connect to server")
            false
        }
    }

    fun disconnect(address: String) {
        clientSockets[address]?.let { socket ->
            try {
                socket.close()
            } catch (e: IOException) {
                Timber.e(e, "Error disconnecting")
            }
        }
        clientSockets.remove(address)
        clientOutputStreams.remove(address)
        _connectedClients.value = clientSockets.keys.toSet()
    }

    fun disconnectAll() {
        clientSockets.forEach { (address, _) ->
            disconnect(address)
        }
    }

    suspend fun sendMessage(address: String, message: WifiDirectMessage): Boolean = withContext(Dispatchers.IO) {
        val outputStream = clientOutputStreams[address]
        if (outputStream == null) {
            Timber.e("No connection to peer")
            return@withContext false
        }

        try {
            synchronized(outputStream) {
                WifiDirectMessageCodec.encode(message, outputStream)
                outputStream.flush()
            }
            Timber.d("Sent message: ${message::class.simpleName}")
            true
        } catch (e: IOException) {
            Timber.e(e, "Failed to send message")
            false
        }
    }

    suspend fun broadcastMessage(message: WifiDirectMessage): Int = withContext(Dispatchers.IO) {
        var successCount = 0
        clientOutputStreams.forEach { (address, _) ->
            if (sendMessage(address, message)) {
                successCount++
            }
        }
        Timber.d("Broadcast message to $successCount clients")
        successCount
    }

    fun isConnectedTo(address: String): Boolean {
        return clientSockets[address]?.isConnected == true
    }

    fun hasAnyConnection(): Boolean {
        return clientSockets.isNotEmpty() || _isServerRunning.value
    }

    companion object {
        const val PORT = 8988
        const val CONNECTION_TIMEOUT = 10_000 // 10 seconds
    }
}
