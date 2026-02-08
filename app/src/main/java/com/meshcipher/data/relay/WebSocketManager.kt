package com.meshcipher.data.relay

import com.meshcipher.data.auth.RelayAuthManager
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.data.remote.dto.QueuedMessage
import com.meshcipher.domain.usecase.ReceiveMessageUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class WebSocketState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED
}

@Singleton
class WebSocketManager @Inject constructor(
    private val relayAuthManager: RelayAuthManager,
    private val appPreferences: AppPreferences,
    private val receiveMessageUseCase: ReceiveMessageUseCase
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(WebSocketState.DISCONNECTED)
    val connectionState: StateFlow<WebSocketState> = _connectionState.asStateFlow()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var userId: String? = null
    private var intentionalClose = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for WebSocket
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS) // OkHttp handles ping/pong
        .build()

    fun connect(userIdValue: String) {
        if (_connectionState.value == WebSocketState.CONNECTING ||
            _connectionState.value == WebSocketState.AUTHENTICATING ||
            _connectionState.value == WebSocketState.CONNECTED
        ) {
            return
        }

        userId = userIdValue
        intentionalClose = false
        reconnectAttempt = 0
        doConnect()
    }

    fun disconnect() {
        intentionalClose = true
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = WebSocketState.DISCONNECTED
    }

    private fun doConnect() {
        _connectionState.value = WebSocketState.CONNECTING

        scope.launch {
            try {
                val token = relayAuthManager.ensureAuthenticated()
                if (token == null) {
                    Timber.w("Cannot connect WebSocket: no auth token")
                    _connectionState.value = WebSocketState.DISCONNECTED
                    scheduleReconnect()
                    return@launch
                }

                val baseUrl = appPreferences.relayServerUrl.first()
                val wsUrl = baseUrl
                    .trimEnd('/')
                    .replace("https://", "wss://")
                    .replace("http://", "ws://") + "/api/v1/relay/ws"

                val request = Request.Builder()
                    .url(wsUrl)
                    .build()

                webSocket = client.newWebSocket(request, createListener(token))
            } catch (e: Exception) {
                Timber.e(e, "WebSocket connect failed")
                _connectionState.value = WebSocketState.DISCONNECTED
                scheduleReconnect()
            }
        }
    }

    private fun createListener(token: String): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.d("WebSocket opened, sending auth")
                _connectionState.value = WebSocketState.AUTHENTICATING

                val authMsg = JSONObject().apply {
                    put("type", "auth")
                    put("token", token)
                }
                webSocket.send(authMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "authenticated" -> {
                            Timber.d("WebSocket authenticated")
                            _connectionState.value = WebSocketState.CONNECTED
                            reconnectAttempt = 0

                            // Fetch any messages queued while disconnected
                            scope.launch {
                                try {
                                    val uid = userId ?: return@launch
                                    receiveMessageUseCase(uid)
                                } catch (e: Exception) {
                                    Timber.w(e, "Initial message fetch failed")
                                }
                            }
                        }
                        "new_message" -> {
                            Timber.d("WebSocket received push message")
                            val msgJson = json.optJSONObject("message")
                            if (msgJson != null) {
                                val queued = QueuedMessage(
                                    id = msgJson.getString("id"),
                                    senderId = msgJson.getString("sender_id"),
                                    recipientId = msgJson.getString("recipient_id"),
                                    encryptedContent = msgJson.getString("encrypted_content"),
                                    contentType = msgJson.optInt("content_type", 0),
                                    queuedAt = msgJson.optString("queued_at", "")
                                )
                                scope.launch {
                                    try {
                                        val uid = userId ?: return@launch
                                        receiveMessageUseCase.processAndAcknowledge(queued, uid)
                                    } catch (e: Exception) {
                                        Timber.w(e, "Direct message processing failed, falling back to poll")
                                        try {
                                            val uid = userId ?: return@launch
                                            receiveMessageUseCase(uid)
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        }
                        "error" -> {
                            val msg = json.optString("message", "Unknown error")
                            Timber.e("WebSocket error: %s", msg)
                            if (msg == "Token expired" || msg == "Invalid token") {
                                webSocket.close(1000, "Re-authenticating")
                            }
                        }
                        "pong" -> { /* Expected response to our ping */ }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse WebSocket message")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.w(t, "WebSocket failure (code: %s)", response?.code)
                _connectionState.value = WebSocketState.DISCONNECTED
                this@WebSocketManager.webSocket = null
                if (!intentionalClose) {
                    scheduleReconnect()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Timber.d("WebSocket closing: %d %s", code, reason)
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.d("WebSocket closed: %d %s", code, reason)
                _connectionState.value = WebSocketState.DISCONNECTED
                this@WebSocketManager.webSocket = null
                if (!intentionalClose) {
                    scheduleReconnect()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (intentionalClose) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = minOf(
                1000L * (1L shl minOf(reconnectAttempt, 5)),
                30_000L
            )
            reconnectAttempt++
            Timber.d("WebSocket reconnecting in %dms (attempt %d)", delayMs, reconnectAttempt)
            delay(delayMs)
            doConnect()
        }
    }
}
