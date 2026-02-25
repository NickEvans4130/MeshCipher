package com.meshcipher.desktop.network

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RelayMessage(
    val type: String,
    val senderId: String = "",
    val recipientId: String = "",
    val payload: String = "",
    val token: String = ""
)

enum class RelayState { DISCONNECTED, CONNECTING, CONNECTED }

class RelayTransport(
    private val relayBaseUrl: String,
    private val deviceId: String,
    private val authToken: String
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        install(WebSockets)
        install(ContentNegotiation) { json(json) }
    }

    private val _state = MutableStateFlow(RelayState.DISCONNECTED)
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<RelayMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<RelayMessage> = _incomingMessages.asSharedFlow()

    private var wsSession: DefaultClientWebSocketSession? = null
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun connect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch { connectWithBackoff() }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        scope.launch {
            wsSession?.close(CloseReason(CloseReason.Codes.NORMAL, "client disconnect"))
            _state.value = RelayState.DISCONNECTED
        }
    }

    suspend fun sendMessage(recipientId: String, encryptedPayload: String) {
        val session = wsSession ?: return
        val msg = RelayMessage(
            type = "message",
            senderId = deviceId,
            recipientId = recipientId,
            payload = encryptedPayload
        )
        session.send(Frame.Text(json.encodeToString(msg)))
    }

    private suspend fun connectWithBackoff() {
        var delayMs = 1_000L
        while (true) {
            try {
                _state.value = RelayState.CONNECTING
                val wsUrl = relayBaseUrl
                    .replace("https://", "wss://")
                    .replace("http://", "ws://")
                    .trimEnd('/') + "/api/v1/relay/ws"

                client.webSocket(
                    urlString = "$wsUrl?token=${authToken}&deviceId=${deviceId}"
                ) {
                    wsSession = this
                    _state.value = RelayState.CONNECTED
                    delayMs = 1_000L // reset backoff on success

                    // Auth handshake
                    val authMsg = RelayMessage(type = "auth", token = authToken, senderId = deviceId)
                    send(Frame.Text(json.encodeToString(authMsg)))

                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            runCatching {
                                val msg = json.decodeFromString<RelayMessage>(frame.readText())
                                _incomingMessages.tryEmit(msg)
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                // Connection failed — fall through to backoff
            }

            wsSession = null
            _state.value = RelayState.DISCONNECTED
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(30_000L)
        }
    }
}
