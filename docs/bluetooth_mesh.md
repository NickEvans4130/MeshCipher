# Bluetooth Mesh

The Bluetooth Mesh provides offline, serverless communication using BLE (Bluetooth Low Energy) advertising/scanning and GATT (Generic Attribute Profile) for message exchange. Messages are relayed hop-by-hop through a flooding mesh with TTL-based loop prevention.

## Architecture

```
BluetoothMeshManager       -- BLE advertising and scanning (peer discovery)
GattServerManager          -- GATT server/client for message exchange
MeshRouter                 -- Routing logic (flooding with loop prevention)
BluetoothMeshTransport     -- Transport layer integration
BluetoothMeshService       -- Foreground service (notification ID 1001)
MeshMessage                -- Binary wire format with TTL and hop tracking
```

## Peer Discovery

### BLE Advertising (`BluetoothMeshManager.startAdvertising`)

Devices broadcast custom BLE advertisements containing:
- Hashed Device ID
- Hashed User ID

Advertisements are low-power and continuous while the mesh service is running.

### BLE Scanning (`BluetoothMeshManager.startScanning`)

Devices scan for advertisements matching the MeshCipher service UUID. Discovered peers are added to the neighbor table:

```kotlin
data class MeshPeer(
    val deviceId: String,
    val userId: String,
    val rssi: Int,           // Signal strength
    val lastSeen: Long,      // Timestamp
    val isInRange: Boolean   // Based on RSSI threshold and recency
)
```

### Stale Peer Removal

The maintenance loop (every 30 seconds) calls `removeStalePeers()` to mark peers not seen recently as out of range, and `cleanupStaleRoutes()` to remove expired routing table entries.

## GATT Protocol

### GATT Server (`GattServerManager.startGattServer`)

Exposes two characteristics:

| Characteristic | Access | Purpose |
|---------------|--------|---------|
| Message Characteristic | Write | Incoming encrypted mesh packets |
| ACK Characteristic | Notify | Delivery receipts |

### Message Exchange

1. **Sender** discovers peer via BLE scan
2. **Sender** connects to peer's GATT server
3. **Sender** writes `MeshMessage` bytes to Message Characteristic
4. **Receiver** reads characteristic, processes message
5. **Receiver** sends ACK via ACK Characteristic (Notify)

## MeshMessage Binary Format

### Structure

```kotlin
data class MeshMessage(
    val id: String,              // UUID
    val originDeviceId: String,  // Original sender's device
    val originUserId: String,    // Original sender's user ID
    val destinationUserId: String,
    val timestamp: Long,
    val ttl: Int,                // Time to live (default: 5)
    val hopCount: Int,           // Incremented at each relay
    val path: String,            // Comma-separated device IDs
    val encryptedPayload: ByteArray  // Signal Protocol ciphertext
)
```

### Wire Format (ByteBuffer serialization)

```
[2 bytes: id length (short)]
[N bytes: id string]
[2 bytes: originDeviceId length (short)]
[N bytes: originDeviceId string]
[2 bytes: originUserId length (short)]
[N bytes: originUserId string]
[2 bytes: destinationUserId length (short)]
[N bytes: destinationUserId string]
[8 bytes: timestamp (long)]
[4 bytes: ttl (int)]
[4 bytes: hopCount (int)]
[2 bytes: path length (short)]
[N bytes: path string (comma-separated device IDs)]
[4 bytes: payload length (int)]
[N bytes: encrypted payload]
```

All multi-byte integers are big-endian (ByteBuffer default).

## Mesh Routing (`MeshRouter`)

### routeMessage() Algorithm

```
1. LOOP PREVENTION
   - Check message.id against seenMessages LRU cache (capacity: 1000)
   - If seen: drop message, return failure
   - Add to seenMessages cache

2. TTL CHECK
   - If message.hopCount >= message.ttl: drop message, return failure

3. DIRECT ROUTE LOOKUP
   - Check RoutingTable for destinationUserId
   - If route exists and nextHop device is reachable:
     -> Send directly to nextHopDeviceId
     -> Return success

4. FLOODING
   - Get all neighbors where isInRange == true
   - Increment message.hopCount
   - Append own deviceId to message.path
   - For each neighbor:
     - Skip if neighbor.deviceId == message.originDeviceId
     - Skip if neighbor.deviceId is in message.path
     - Send message to neighbor via GATT
   - Return success if any neighbor accepted the message
```

### Route Learning

When a message is received (even if not addressed to this device), the router learns the path:

```kotlin
fun handleIncomingMessage(message: MeshMessage, fromDeviceId: String) {
    routingTable.addRoute(
        userId = message.originUserId,
        nextHop = fromDeviceId,
        hopCount = message.hopCount
    )
}
```

The routing table maintains the shortest known path to each userId. Direct routes are preferred over flooding for subsequent messages to the same recipient.

### Routing Table

```kotlin
data class RouteEntry(
    val userId: String,
    val nextHopDeviceId: String,
    val hopCount: Int,
    val lastUpdated: Long
)
```

- Routes with fewer hops replace routes with more hops
- Routes older than the stale threshold are cleaned up periodically
- `canReach(userId)` returns true if a route exists and the next hop is in range

## BluetoothMeshTransport

### Sending

```kotlin
suspend fun sendMessage(recipientId: String, encryptedContent: ByteArray): Result<String> {
    // 1. Verify Bluetooth enabled and peers discovered
    // 2. Create MeshMessage:
    //    id = UUID
    //    originDeviceId = this device's ID
    //    originUserId = this user's ID
    //    destinationUserId = recipientId
    //    ttl = 5
    //    hopCount = 0
    //    path = ""
    //    encryptedPayload = encryptedContent
    // 3. meshRouter.routeMessage(message)
    // 4. Return Result.success(messageId)
}
```

### Availability

```kotlin
fun isAvailable(): Boolean =
    bluetoothEnabled && discoveredPeers.isNotEmpty()

fun canReachRecipient(recipientId: String): Boolean =
    meshRouter.canReach(recipientId)
```

## Foreground Service (`BluetoothMeshService`)

### Notification Channels

| Channel | ID | Importance | Purpose |
|---------|----|------------|---------|
| Mesh Network | "mesh_network" | LOW | Persistent service notification |
| Messages | "messages" | HIGH | Incoming message alerts |

### Service Lifecycle

**onCreate():**
1. Create notification channels
2. Start foreground (notification ID 1001)
3. Launch coroutines:
   - Start BLE advertising and scanning
   - Start GATT server
   - Collect incoming messages
   - Start maintenance loop (30s interval)

**Message Processing (incoming):**
```
1. Receive MeshMessage from GATT server
2. If destinationUserId == myUserId:
   a. Look up sender contact
   b. Decrypt with SignalProtocolManager
   c. Create/get conversation
   d. Save message to database
   e. Show notification (HIGH importance channel)
3. Else if TTL remaining:
   a. meshRouter.routeMessage(message)  // Relay to next hop
```

**Maintenance Loop (every 30 seconds):**
- `bluetoothMeshManager.removeStalePeers()`
- `meshRouter.cleanupStaleRoutes()`

**onDestroy():**
- Cancel service coroutine scope
- Stop BLE advertising and scanning
- Stop GATT server

### Message Notifications

Incoming messages trigger notifications on the HIGH importance "messages" channel:
- Title: sender's display name
- Content: message text
- Intent: opens `MainActivity` with `conversationId` extra
- Auto-cancel, vibration enabled

## Limitations

- **Bandwidth**: BLE GATT has limited MTU (typically 20-512 bytes). Large messages require multiple GATT writes.
- **Range**: ~30-100 feet per hop depending on environment.
- **Latency**: Multi-hop messages incur delay at each relay.
- **Media**: Not practical for large media files over Bluetooth. WiFi Direct or internet transports are preferred for media.
- **Discovery**: Requires both devices to have Bluetooth enabled and mesh service running.
