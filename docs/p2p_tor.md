# P2P Tor (Hidden Service Messaging)

P2P Tor enables direct device-to-device messaging over the internet via Tor hidden services (.onion addresses). No relay server is involved. Both endpoints are anonymous.

## Architecture

```
P2PConnectionManager      -- Orchestrates full lifecycle (start/stop/send)
EmbeddedTorManager         -- Binds to TorService, manages control port, creates hidden services
HiddenServiceServer        -- ServerSocket on localhost, accepts incoming connections via hidden service
P2PClient                  -- SOCKS5 connection pool for outgoing messages to .onion addresses
P2PTorService              -- Foreground service (notification ID 1002)
P2PTransport               -- Transport layer integration
P2PMessage                 -- Length-prefixed JSON wire format
```

## Connection Lifecycle

### Starting P2P Tor (`P2PConnectionManager.startP2P`)

```
1. HiddenServiceServer.start()
   -> Binds ServerSocket(0, 50, "127.0.0.1")
   -> Returns ephemeral localPort (e.g., 43521)

2. EmbeddedTorManager.start()
   -> Sends ACTION_START intent to TorService (Guardian Project tor-android)
   -> Binds to TorService with BIND_AUTO_CREATE
   -> Registers LocalBroadcastReceiver for TorService.ACTION_STATUS

3. Wait for bootstrap (polling loop):
   -> Every 1 second: TorControlConnection.getInfo("status/bootstrap-phase")
   -> Parse "PROGRESS=XX" from response
   -> Update bootstrapPercent in status flow
   -> Timeout: 120 attempts (2 minutes)
   -> Extract SOCKS port from TorService.socksPort or "net/listeners/socks"

4. EmbeddedTorManager.createHiddenService(localPort)
   -> Check EncryptedSharedPreferences for saved ED25519-V3 private key
   -> If first time: TorControlConnection.addOnion("NEW:ED25519-V3", {80 -> "127.0.0.1:localPort"})
   -> If returning: TorControlConnection.addOnion(savedPrivateKey, {80 -> "127.0.0.1:localPort"})
   -> Returns: onionAddress (without .onion suffix), privateKey
   -> Save private key to EncryptedSharedPreferences for persistence
   -> Save onionAddress to AppPreferences

5. State: RUNNING (onionAddress available)
```

### Hidden Service Port Mapping

```
External (Tor network):  .onion:80
  -> Tor daemon routes to:  127.0.0.1:{localPort}
  -> HiddenServiceServer accepts connection
```

The hidden service maps virtual port 80 to the ephemeral local port where `HiddenServiceServer` is listening. Tor handles all routing through the network.

### Key Persistence

The ED25519-V3 private key for the hidden service is stored in EncryptedSharedPreferences. On restart, the same key is loaded, producing the same .onion address. This gives each device a stable anonymous identity.

## Wire Protocol (`P2PMessage`)

### Message Structure

```kotlin
data class P2PMessage(
    val type: Type,         // TEXT, MEDIA, ACK, PING, PONG
    val messageId: String,  // UUID
    val senderId: String,   // Sender's userId
    val recipientId: String,// Recipient's userId
    val timestamp: Long,    // Unix millis
    val payload: String?    // Base64-encoded data (nullable for ACK/PING/PONG)
)

enum class Type { TEXT, MEDIA, ACK, PING, PONG }
```

### Serialization (Length-Prefixed JSON)

**Write:**
```kotlin
fun writeToStream(output: OutputStream) {
    val dos = DataOutputStream(output)
    val bytes = toJson().toByteArray(Charsets.UTF_8)
    dos.writeInt(bytes.size)  // 4-byte big-endian length prefix
    dos.write(bytes)           // JSON payload
    dos.flush()
}
```

**Read:**
```kotlin
fun readFromStream(input: InputStream): P2PMessage {
    val dis = DataInputStream(input)
    val length = dis.readInt()
    require(length in 1..16_777_216)  // Max 16MB
    val bytes = ByteArray(length)
    dis.readFully(bytes)
    return fromJson(String(bytes, Charsets.UTF_8))
}
```

### Wire Format

```
[4 bytes: message length (big-endian int32)]
[N bytes: UTF-8 JSON payload]
```

### Message Types

| Type | Payload | Response | Purpose |
|------|---------|----------|---------|
| TEXT | Base64(Signal-encrypted ciphertext) | ACK | Text message delivery |
| MEDIA | Base64(Signal-encrypted MediaMessageEnvelope JSON) | None | Media message delivery |
| ACK | None | None | Acknowledgment of TEXT receipt |
| PING | None | PONG | Keep-alive (not currently used) |
| PONG | None | None | Keep-alive response (not currently used) |

## Incoming Messages (`HiddenServiceServer`)

```
1. Accept TCP connection on ServerSocket
2. Generate peerId (UUID) for connection tracking
3. Store in connectedPeers ConcurrentHashMap
4. Loop:
   a. P2PMessage.readFromStream(inputStream)
   b. Emit to incomingMessages SharedFlow
   c. If message.type == TEXT:
      - Create ACK message (swap sender/recipient, type=ACK, same messageId)
      - P2PMessage.writeToStream(outputStream)
5. On disconnect: remove from connectedPeers
```

## Outgoing Messages (`P2PClient`)

### Connection Pooling

```kotlin
private val connections = ConcurrentHashMap<String, Socket>()

fun getOrCreateConnection(onionAddress: String): Socket {
    val existing = connections[onionAddress]
    if (existing != null && !existing.isClosed && existing.isConnected) {
        return existing
    }
    // Create new SOCKS5 connection
    val proxy = embeddedTorManager.getSocksProxy()
    val socket = Socket(proxy)
    val address = InetSocketAddress.createUnresolved("$onionAddress.onion", 80)
    socket.connect(address, 120_000)  // 120 second timeout
    connections[onionAddress] = socket
    return socket
}
```

Key detail: `InetSocketAddress.createUnresolved()` ensures the .onion address is resolved by the Tor SOCKS proxy, not by the local DNS resolver.

### Send Flow

```
1. Get or create connection to recipient's .onion address
2. message.writeToStream(socket.outputStream)
3. If type == TEXT:
   - Read ACK via P2PMessage.readFromStream(socket.inputStream)
   - Verify ACK.messageId matches sent message
4. On IOException:
   - Remove failed connection from pool
   - Throw exception (TransportManager handles fallback)
```

## P2PTransport Integration

`P2PTransport` bridges `P2PConnectionManager` to the `TransportManager` interface.

### Sending

```kotlin
suspend fun sendMessage(recipientId: String, encryptedContent: ByteArray, contentType: Int): Result<String> {
    // 1. Verify P2PConnectionManager is running
    // 2. Look up recipient contact to get onionAddress
    // 3. Create P2PMessage:
    //    type = if (contentType == 1) MEDIA else TEXT
    //    payload = Base64.encode(encryptedContent)
    // 4. p2pConnectionManager.sendMessage(onionAddress, message)
    // 5. Return Result.success(messageId)
}
```

### Receiving

```kotlin
// Collects from p2pConnectionManager.incomingMessages
p2pConnectionManager.incomingMessages.collect { message ->
    when (message.type) {
        TEXT  -> deliverTextMessage(message)
        MEDIA -> deliverMediaMessage(message)
        ACK   -> handleAck(message)
        PING  -> sendPong(message)
        PONG  -> { /* no-op */ }
    }
}
```

### Availability Check

```kotlin
fun isAvailable(): Boolean =
    p2pConnectionManager.isRunning()

fun canReachRecipient(recipientId: String): Boolean {
    val contact = contactDao.getContact(recipientId)
    return contact?.onionAddress != null && isAvailable()
}
```

## Foreground Service (`P2PTorService`)

- **Notification ID**: 1002
- **Channel**: "p2p_tor", IMPORTANCE_LOW (silent, persistent)
- **Behavior**: START_STICKY (restart if killed by system)
- `onCreate()`: starts `P2PConnectionManager.startP2P()`
- `onDestroy()`: calls `P2PConnectionManager.stopP2P()`
- No binding interface (pure background service)

## .onion Address Exchange

Users share their .onion address via the `ContactCard` QR code format. When a contact is added via QR scan, the `onionAddress` field is saved in `ContactEntity`. The P2P Tor transport uses this address to establish connections.

The .onion address is also stored in `AppPreferences` and displayed in the P2P Tor settings screen.
