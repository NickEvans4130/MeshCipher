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

**Endpoints:**

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/relay/message` | Send encrypted message |
| GET | `/api/v1/relay/messages/{recipientId}` | Poll for queued messages |
| POST | `/api/v1/relay/messages/{recipientId}/ack` | Acknowledge received messages |
| POST | `/api/v1/register` | Register device with relay |
| GET | `/api/v1/health` | Server health check |

**Send payload:**
```json
{
    "senderId": "abc123...",
    "recipientId": "def456...",
    "encryptedContent": "Base64(Signal-encrypted bytes)",
    "contentType": 0
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
3. `MediaFileManager.saveMedia(mediaId, bytes, mediaType)` writes to `files/media/{type}/{mediaId}`
4. Message entity created with `MediaAttachment` containing local file path
5. ChatScreen renders media inline based on `mediaType` (IMAGE, VIDEO, VOICE)
