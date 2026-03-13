package com.meshcipher.desktop.network

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import com.meshcipher.desktop.data.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Flat format used for desktop→desktop relay messages sent via WebSocket.
 */
@Serializable
data class RelayMessage(
    val type: String,
    val senderId: String = "",
    val recipientId: String = "",
    val payload: String = "",
    val token: String = "",
    /** Nested body present when the relay server delivers an HTTP-posted message. */
    val message: RelayPushBody? = null
)

/**
 * Body of messages pushed by the relay server when it delivers an HTTP POST.
 * The relay sends: {"type":"new_message","message":{...}}
 */
@Serializable
data class RelayPushBody(
    @SerialName("id") val id: String = "",
    @SerialName("sender_id") val senderId: String = "",
    @SerialName("recipient_id") val recipientId: String = "",
    @SerialName("encrypted_content") val encryptedContent: String = "",
    @SerialName("content_type") val contentType: Int = 0,
    @SerialName("queued_at") val queuedAt: String? = null
)

/** Normalised view of a relay message regardless of which format it arrived in. */
data class NormalizedMessage(
    val senderId: String,
    val recipientId: String,
    /** Raw payload — may be base64 (from relay push) or plain JSON (desktop→desktop). */
    val rawPayload: String,
    val contentType: Int
)

fun RelayMessage.normalize(): NormalizedMessage? {
    val body = message
    return if (body != null && body.senderId.isNotBlank()) {
        // Relay server push format
        NormalizedMessage(
            senderId = body.senderId,
            recipientId = body.recipientId,
            rawPayload = body.encryptedContent,
            contentType = body.contentType
        )
    } else if (senderId.isNotBlank() && payload.isNotBlank()) {
        // Desktop-to-desktop WebSocket format
        NormalizedMessage(
            senderId = senderId,
            recipientId = recipientId,
            rawPayload = payload,
            contentType = 0
        )
    } else {
        null
    }
}

enum class RelayState { DISCONNECTED, CONNECTING, CONNECTED, TOR_UNAVAILABLE }

class RelayTransport(
    private val relayBaseUrl: String,
    private val deviceId: String,
    private val authToken: String,
    private val userId: String,
    private val torManager: TorManager? = null
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
        // Reconnect whenever TOR toggle changes
        scope.launch {
            SettingsRepository.torEnabled.drop(1).collect { reconnect() }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        clearSocksProxy()
        torManager?.clearSystemProxy()
        scope.launch {
            wsSession?.close(CloseReason(CloseReason.Codes.NORMAL, "client disconnect"))
            _state.value = RelayState.DISCONNECTED
        }
    }

    private fun reconnect() {
        reconnectJob?.cancel()
        scope.launch {
            wsSession?.close(CloseReason(CloseReason.Codes.NORMAL, "settings change"))
        }
        reconnectJob = scope.launch { connectWithBackoff() }
    }

    /** Apply SOCKS5 proxy for system TOR (port 9050) via Java system properties. */
    private fun applySocksProxy() {
        System.setProperty("socksProxyHost", "127.0.0.1")
        System.setProperty("socksProxyPort", "9050")
        System.setProperty("socksNonProxyHosts", "")
    }

    private fun clearSocksProxy() {
        System.clearProperty("socksProxyHost")
        System.clearProperty("socksProxyPort")
        System.clearProperty("socksNonProxyHosts")
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

    /** Exposed so MessagingManager can call this on a polling timer. */
    suspend fun pollQueuedMessages() = fetchQueuedMessages()

    /** Send an arbitrary payload with a specific content_type via HTTP POST. */
    suspend fun sendRawMessage(recipientId: String, payload: ByteArray, contentType: Int) {
        val base = relayBaseUrl.trimEnd('/')
        val encoded = java.util.Base64.getEncoder().encodeToString(payload)
        client.post("$base/api/v1/relay/message") {
            header(HttpHeaders.Authorization, "Bearer $authToken")
            contentType(ContentType.Application.Json)
            setBody("""{"sender_id":"$userId","recipient_id":"$recipientId","encrypted_content":"$encoded","content_type":$contentType}""")
        }
    }

    private suspend fun connectWithBackoff() {
        var delayMs = 1_000L
        while (true) {
            try {
                if (SettingsRepository.torEnabled.value) {
                    // Check that TOR is reachable before trying to connect
                    val torReachable = isSocksReachable("127.0.0.1", 9050)
                    if (!torReachable) {
                        clearSocksProxy()
                        torManager?.clearSystemProxy()
                        _state.value = RelayState.TOR_UNAVAILABLE
                        delay(15_000L) // retry less aggressively when TOR is missing
                        continue
                    }
                    applySocksProxy()
                } else {
                    clearSocksProxy()
                    torManager?.clearSystemProxy()
                }

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
                    delayMs = 1_000L

                    val authMsg = RelayMessage(type = "auth", token = authToken, senderId = deviceId)
                    send(Frame.Text(json.encodeToString(authMsg)))

                    // Fetch any messages queued before this connection
                    scope.launch { fetchQueuedMessages() }

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
            } catch (_: Exception) {
                // fall through to backoff
            }

            wsSession = null
            _state.value = RelayState.DISCONNECTED
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(30_000L)
        }
    }

    /** Returns true if a TCP connection to [host]:[port] succeeds within 2 seconds. */
    private fun isSocksReachable(host: String, port: Int): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(host, port), 2_000)
            true
        }
    } catch (_: Exception) { false }

    private suspend fun fetchQueuedMessages() {
        val base = relayBaseUrl.trimEnd('/')
        runCatching {
            val response = client.get("$base/api/v1/relay/messages/$userId") {
                header(HttpHeaders.Authorization, "Bearer $authToken")
            }
            if (!response.status.isSuccess()) return

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val messages = body["messages"]?.jsonArray ?: return
            val ackIds = mutableListOf<String>()

            for (element in messages) {
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: continue
                val senderId = obj["sender_id"]?.jsonPrimitive?.content ?: continue
                val recipientId = obj["recipient_id"]?.jsonPrimitive?.content ?: ""
                val encryptedContent = obj["encrypted_content"]?.jsonPrimitive?.content ?: continue
                val contentType = obj["content_type"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

                val syntheticMsg = RelayMessage(
                    type = "new_message",
                    message = RelayPushBody(
                        id = id,
                        senderId = senderId,
                        recipientId = recipientId,
                        encryptedContent = encryptedContent,
                        contentType = contentType
                    )
                )
                _incomingMessages.emit(syntheticMsg)
                ackIds.add(id)
            }

            if (ackIds.isNotEmpty()) {
                runCatching {
                    client.post("$base/api/v1/relay/messages/$userId/ack") {
                        header(HttpHeaders.Authorization, "Bearer $authToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"message_ids":${ackIds.joinToString(",", "[", "]") { "\"$it\"" }}}""")
                    }
                }
            }
        }
    }
}
