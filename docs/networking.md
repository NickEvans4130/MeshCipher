# Transport Layer

MeshCipher uses a `TransportManager` singleton that selects the best available transport for each message based on the user's configured `ConnectionMode` and current network conditions.

## Connection Modes

```kotlin
enum class ConnectionMode {
    DIRECT,     // Internet relay server
    TOR_RELAY,  // Internet relay via Tor SOCKS proxy
    P2P_ONLY,   // WiFi Direct + Bluetooth Mesh only (no internet)
    P2P_TOR     // P2P Tor > WiFi Direct > Bluetooth Mesh
}
```

Stored in DataStore as a string preference. Read by `TransportManager` via `AppPreferences.connectionMode` Flow.

## Custom Relay Server

The relay server URL is configurable per-device via Settings > Relay Server. The default points to the project-hosted relay. Users can enter any URL pointing to a self-hosted instance of the relay server.

- **DynamicBaseUrlInterceptor** rewrites every outgoing OkHttp request to the user's configured relay URL at request time (no app restart needed).
- Both the Direct and Tor Relay transports use this interceptor, so changing the URL applies to both modes.
- P2P modes (Bluetooth, WiFi Direct, P2P Tor) do not use the relay server and are unaffected.
- Both sender and recipient must be connected to the same relay server for Direct/Tor Relay message delivery to work.

See [Self-Hosting](self_hosting.md) for relay server setup instructions.

## Transport Priority per Mode

### `DIRECT` / `TOR_RELAY`

```
1. P2P Tor       (if running AND recipient .onion reachable)
2. WiFi Direct   (if connected)
3. Internet       (DIRECT: plain HTTPS / TOR_RELAY: SOCKS5 proxy)
4. Bluetooth Mesh (fallback)
```

### `P2P_ONLY`

```
1. WiFi Direct   (if connected)
2. Bluetooth Mesh (fallback)
```

No internet transports attempted.

### `P2P_TOR`

```
1. P2P Tor       (primary, via .onion hidden service)
2. WiFi Direct   (fallback if Tor unavailable)
3. Bluetooth Mesh (last resort)
```

## TransportManager.sendWithFallback()

```kotlin
suspend fun sendWithFallback(
    recipientId: String,
    encryptedContent: ByteArray,
    contentType: Int = 0  // 0=text, 1=media
): Result<String>
```

The method tries each transport in priority order. On failure, it falls through to the next transport. Returns `Result.success(messageId)` on first success, or `Result.failure(Exception)` if all transports fail.

### contentType Routing

- `contentType = 0` (text): Sent as-is to all transports
- `contentType = 1` (media):
  - WiFi Direct: Routed through `sendFile()` for chunked transfer (64KB chunks)
  - P2P Tor: Sent as `P2PMessage` with `type = MEDIA`
  - Internet: Sent as `RelayMessageRequest` with `contentType = 1`
  - Bluetooth Mesh: Sent as mesh payload (size-limited)

## Transport Implementations

### InternetTransport

REST API client using Retrofit + OkHttp.

**Authentication:**

All relay endpoints require a valid JWT token. The `AuthInterceptor` (OkHttp interceptor) reads the token from `TokenStorage` and attaches `Authorization: Bearer <token>` to outgoing requests. Public endpoints (`/auth/*`, `/health`, `/register`) are excluded from token injection. See [Cryptography - Authentication](cryptography.md#authentication) for the challenge-response flow.

**Endpoints:**

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/v1/auth/challenge` | No | Request authentication challenge |
| POST | `/api/v1/auth/verify` | No | Submit signed challenge for JWT |
| POST | `/api/v1/register` | No | Register device with relay |
| GET | `/api/v1/health` | No | Server health check |
| POST | `/api/v1/relay/message` | JWT | Send encrypted message |
| GET | `/api/v1/relay/messages/{recipientId}` | JWT | Poll for queued messages |
| POST | `/api/v1/relay/messages/{recipientId}/ack` | JWT | Acknowledge received messages |

**Server-Side Security:**

| Protection | Detail |
|------------|--------|
| Rate limiting | Flask-Limiter: 200/min default, 10/min auth, 60/min relay, 30/min health |
| Identity verification | `sender_id` must match JWT `user_id`; recipients can only fetch/ack own messages |
| Input sanitization | All string inputs trimmed and truncated to 255 chars |
| Request size limit | 10 MB max body (`MAX_CONTENT_LENGTH`) |
| Mailbox bombing | Max 500 queued messages per recipient |
| Response pagination | Max 100 messages per poll request |
| Security headers | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `CSP: default-src 'none'`, `Cache-Control: no-store`, `Referrer-Policy: no-referrer` |
| Signature verification | ECDSA P-256/SHA-256 via Python `cryptography` library |

**Send payload:**
```json
{
    "sender_id": "abc123...",
    "recipient_id": "def456...",
    "encrypted_content": "Base64(Signal-encrypted bytes)",
    "content_type": 0
}
```

**Tor Relay variant:**
- Same OkHttp client configured with SOCKS5 proxy
- `Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050))`
- 60-second connect/read/write timeouts
- Proxy created lazily and synchronized

### WifiDirectTransport

See [WiFi Direct](wifi_direct.md) for full details.

### BluetoothMeshTransport

See [Bluetooth Mesh](bluetooth_mesh.md) for full details.

### P2PTransport

See [P2P Tor](p2p_tor.md) for full details.

## Real-Time WebSocket Delivery

When the app is in the foreground, messages are delivered instantly via a WebSocket connection to the relay server.

### WebSocketManager

Singleton (`@Singleton`) managed by Hilt. Uses OkHttp's built-in `WebSocket` client.

**Connection lifecycle:**
1. `MeshCipherApplication.onCreate()` pre-warms the JWT auth token on the IO thread
2. Once authenticated, opens a WebSocket to `wss://{relay}/api/v1/relay/ws`
3. Sends `{"type":"auth","token":"<JWT>"}` as the first message
4. Server validates JWT and registers the connection for the user
5. Server pushes `{"type":"new_message",...}` when messages arrive for this user
6. Client processes pushed messages directly via `ReceiveMessageUseCase.processAndAcknowledge()`

**Reconnection:**
- Exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s (max 30s)
- Automatic reconnect on failure or close
- OkHttp ping interval: 25 seconds (keeps connection alive through proxies)

**Foreground/background management:**
- `ProcessLifecycleOwner.onStart()`: reconnect WebSocket
- `ProcessLifecycleOwner.onStop()`: disconnect WebSocket
- WebSocket is only active when the app is in the foreground

**Connection state:**
```kotlin
enum class WebSocketState { DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED }
```

Exposed as `StateFlow<WebSocketState>` and observed by ViewModels.

### Fallback Polling

When the WebSocket is disconnected (background, network issues), HTTP polling provides fallback delivery:

- **ConversationsViewModel**: 15-second poll interval, only when WebSocket is not connected
- **ChatViewModel**: 3-second poll interval with immediate first poll on chat open, only when WebSocket is not connected
- **WorkManager**: 15-minute background sync (catches anything missed while app was killed)

### Server-Side WebSocket

The relay server uses `flask-sock` for raw WebSocket support:

- `@sock.route("/api/v1/relay/ws")` endpoint
- Active connections tracked in `ws_connections` dict (user_id -> WebSocket)
- When `POST /api/v1/relay/message` receives a message, it checks if the recipient has an active WebSocket:
  - **Connected**: Message pushed immediately via WebSocket, marked as delivered (status: `"pushed"`)
  - **Not connected**: Message queued for HTTP polling (status: `"queued"`)
- Gunicorn runs with gevent async workers (`-k gevent -w 1`) to support concurrent WebSocket connections

## Message Reception

Each transport has its own receive path, but all converge to the same processing logic:

1. Encrypted bytes received from transport
2. Look up sender contact by userId or signalProtocolAddress
3. Decrypt with `SignalProtocolManager.decryptMessage()`
4. Get or create conversation for sender
5. Insert `MessageEntity` into Room database with status `DELIVERED`
6. Increment unread count on conversation
7. UI observes `Flow<List<Message>>` and updates automatically

### Media Reception

1. Encrypted payload parsed as `MediaMessageEnvelope` JSON
2. `MediaEncryptor.decrypt(encryptedData, key, iv)` produces raw media bytes
3. `MediaFileManager.saveMedia(mediaId, bytes, mediaType)` encrypts with per-file AES-256-GCM key and writes ciphertext to `files/media_encrypted/{type}/{mediaId}`
4. Message entity created with `MediaAttachment` containing encrypted file path
5. ChatScreen decrypts media on demand: images to byte arrays via `decryptMedia()`, video/voice to temp files via `decryptMediaToTempFile()`
