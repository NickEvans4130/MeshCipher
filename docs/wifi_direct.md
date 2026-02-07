# WiFi Direct

WiFi Direct enables high-bandwidth, serverless communication between two Android devices over WiFi P2P (802.11). Range is approximately 100 meters. No router or internet connection required.

## Architecture

```
WifiDirectManager          -- Android WifiP2pManager wrapper (discovery, connection, group)
WifiDirectSocketManager    -- TCP server/client on port 8988
WifiDirectTransport        -- Transport layer integration (send/receive messages and files)
WifiDirectMessage          -- Wire protocol (sealed class: TextMessage, FileTransfer, FileChunk, Ack)
```

## Discovery and Connection

### Discovery (`WifiDirectManager`)

1. `startDiscovery()` calls `WifiP2pManager.discoverPeers()`
2. Android system broadcasts `WIFI_P2P_PEERS_CHANGED_ACTION`
3. BroadcastReceiver calls `requestPeers()` to get peer list
4. Peers emitted to `discoveredPeers: StateFlow<List<WifiP2pDevice>>`

**Both devices must be actively discovering at the same time** to see each other. This is an Android platform requirement for WiFi P2P.

### Connection

1. User taps a discovered peer in the UI
2. `connect(deviceAddress)` creates `WifiP2pConfig` and calls `WifiP2pManager.connect()`
3. Android negotiates group formation (one device becomes Group Owner)
4. `WIFI_P2P_CONNECTION_CHANGED_ACTION` broadcast received
5. `requestConnectionInfo()` returns:
   - `groupFormed: Boolean`
   - `isGroupOwner: Boolean`
   - `groupOwnerAddress: InetAddress`

### Group Owner vs Client

- **Group Owner (GO)**: Starts TCP `ServerSocket` on port 8988, accepts connections from clients
- **Client**: Connects TCP socket to GO's IP address on port 8988
- Role is assigned by Android during group negotiation (not deterministic)

## Socket Protocol (`WifiDirectSocketManager`)

### Server Mode (Group Owner)

```kotlin
ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))
// PORT = 8988
```

- Accepts client connections in a loop
- Each client gets dedicated `ObjectInputStream` / `ObjectOutputStream`
- Connections stored in `ConcurrentHashMap<String, Socket>`
- `broadcastMessage()` sends to all connected clients
- `connectedClients: StateFlow` tracks active connections

### Client Mode

```kotlin
Socket().connect(InetSocketAddress(serverAddress, PORT), TIMEOUT)
```

- Single connection to the group owner
- Receives messages via `ObjectInputStream` in coroutine loop
- All messages emitted to `receivedMessages: SharedFlow<WifiDirectMessage>`

### Serialization

All messages are Java `Serializable` objects sent via `ObjectOutputStream` / `ObjectInputStream`. No custom binary wire format.

## Wire Protocol (`WifiDirectMessage`)

Sealed class hierarchy:

### TextMessage

```kotlin
TextMessage(
    senderId: String,
    recipientId: String,
    timestamp: Long,
    encryptedContent: ByteArray  // Signal Protocol ciphertext
)
```

Carries Signal Protocol-encrypted text messages. The `encryptedContent` is already encrypted before reaching the transport layer.

### FileTransfer (Metadata Header)

```kotlin
FileTransfer(
    senderId: String,
    recipientId: String,
    timestamp: Long,
    fileId: String,        // UUID identifying this transfer
    fileName: String,
    fileSize: Long,        // Total file size in bytes
    mimeType: String,
    totalChunks: Int       // Number of FileChunk messages to expect
)
```

Sent before the chunk stream to allow the receiver to allocate and track the incoming file.

### FileChunk

```kotlin
FileChunk(
    senderId: String,
    recipientId: String,
    timestamp: Long,
    fileId: String,        // Matches FileTransfer.fileId
    chunkIndex: Int,       // 0-based index
    data: ByteArray        // Up to 64KB
)
```

### Acknowledgment

```kotlin
Acknowledgment(
    senderId: String,
    recipientId: String,
    timestamp: Long,
    messageId: String,
    success: Boolean
)
```

## Chunked File Transfer Protocol

Used for media messages (`contentType = 1`) and large payloads.

### Sender Side

```
1. Generate fileId (UUID)
2. Calculate totalChunks = ceil(fileData.size / CHUNK_SIZE)
   CHUNK_SIZE = 65536 (64KB)
3. Send FileTransfer metadata message
4. For i in 0..totalChunks-1:
     offset = i * CHUNK_SIZE
     end = min(offset + CHUNK_SIZE, fileData.size)
     chunkData = fileData.copyOfRange(offset, end)
     Send FileChunk(fileId, i, chunkData)
```

### Receiver Side

```
1. Receive FileTransfer -> create PendingFile entry:
   PendingFile(fileId, fileName, fileSize, mimeType, totalChunks,
               receivedChunks = mutableMapOf<Int, ByteArray>())

2. Receive FileChunk -> store in PendingFile.receivedChunks[chunkIndex]

3. When receivedChunks.size == totalChunks:
   a. Assemble: concatenate chunks 0..N in order
   b. Parse assembled bytes as MediaMessageEnvelope JSON
   c. Decrypt media: MediaEncryptor.decrypt(envelope.encryptedData, key, iv)
   d. Save to local storage: MediaFileManager.saveMedia(mediaId, bytes, type)
   e. Create Message with MediaAttachment
   f. Clean up PendingFile entry
```

### Why Chunking?

WiFi Direct TCP connections can handle large payloads, but chunking provides:
- Progress tracking capability
- Resilience to partial transfer failures
- Consistent protocol with Bluetooth Mesh (which requires chunking due to BLE MTU limits)

## Media Message Flow

When `TransportManager` calls `WifiDirectTransport.sendMessage()` with `contentType = 1`:

```
1. TransportManager detects contentType=1
2. Calls wifiDirectTransport.sendMessage(recipientId, encryptedContent, contentType=1)
3. WifiDirectTransport creates fileId (UUID)
4. Calls sendFile(recipientId, fileId, "media_envelope.json", size, "application/json", encryptedContent)
5. sendFile() executes chunked transfer protocol
6. Receiver assembles chunks -> MediaMessageEnvelope -> decrypt -> save -> Message
```

The `encryptedContent` byte array is the already-Signal-Protocol-encrypted `MediaMessageEnvelope` JSON. It gets chunked for transport, then reassembled and decrypted on the receiver side.

## Connection State Management

```kotlin
WifiDirectTransport.isAvailable(): Boolean
  = wifiDirectManager.isWifiP2pEnabled
    && wifiDirectSocketManager.hasAnyConnection()
```

The transport reports available only when WiFi P2P is enabled at the system level AND at least one socket connection is active (either server with clients, or client connected to server).
