# MeshCipher - Phase 4: Bluetooth Mesh Networking
## Weeks 15-17: Offline Communication & Multi-Hop Routing

---

## Phase 4 Overview

**Goals:**
- Implement Bluetooth Low Energy (BLE) mesh networking
- Enable offline device-to-device communication
- Build multi-hop routing algorithm
- Visualize mesh network topology
- Auto-fallback when internet unavailable

**Deliverables:**
- BLE advertising/scanning working
- Peer discovery within Bluetooth range
- Direct peer-to-peer messaging
- Multi-hop message routing (up to 5 hops)
- Mesh network visualization
- Battery-efficient implementation
- Works completely offline

**Prerequisites:**
- ✅ Phase 3 complete (internet messaging)
- ✅ Phase 3.5 complete (authentication)
- ✅ Phase 3.75 complete (TOR routing)

---

## Bluetooth Mesh Architecture

```
Device A ←BLE→ Device B ←BLE→ Device C ←BLE→ Device D
   |              |              |              |
Message        Relay          Relay         Recipient
Origin         Hop 1         Hop 2         Destination

- Each device advertises presence via BLE
- Devices in range can relay messages
- Messages route through optimal path
- End-to-end encrypted (Signal Protocol)
- Maximum 5 hops to prevent loops
```

**Key Concepts:**
- **Advertising:** Broadcast your presence/availability
- **Scanning:** Discover nearby devices
- **Peer Discovery:** Find devices running MeshCipher
- **Routing Table:** Track which peers can reach which destinations
- **Hop Count:** Number of relays message passes through
- **TTL (Time To Live):** Prevent infinite loops

---

## Step 1: Add Permissions

### AndroidManifest.xml (Updated)

```xml
<manifest>
    <!-- Existing permissions -->
    
    <!-- Bluetooth permissions -->
    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
    
    <!-- Android 12+ Bluetooth permissions -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    
    <!-- Required for BLE -->
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
    
    <application>
        <!-- Existing config -->
        
        <!-- Bluetooth Service -->
        <service
            android:name=".data.bluetooth.BluetoothMeshService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
    </application>
</manifest>
```

---

## Step 2: Domain Models

### MeshPeer.kt

```kotlin
package com.meshcipher.domain.model

data class MeshPeer(
    val deviceId: String,           // Our device ID
    val userId: String,              // User's cryptographic ID
    val displayName: String?,        // If contact exists
    val bluetoothAddress: String,    // BLE MAC address
    val rssi: Int,                   // Signal strength
    val lastSeen: Long,              // Timestamp
    val isContact: Boolean,          // Known contact?
    val hopCount: Int = 1,           // Hops away (1 = direct neighbor)
    val reachableVia: String? = null // Device ID of relay
) {
    val isInRange: Boolean
        get() = hopCount == 1 && (System.currentTimeMillis() - lastSeen) < 30000
    
    val signalStrength: SignalStrength
        get() = when {
            rssi >= -50 -> SignalStrength.EXCELLENT
            rssi >= -70 -> SignalStrength.GOOD
            rssi >= -80 -> SignalStrength.FAIR
            else -> SignalStrength.WEAK
        }
}

enum class SignalStrength {
    EXCELLENT, GOOD, FAIR, WEAK
}
```

### MeshMessage.kt

```kotlin
package com.meshcipher.domain.model

data class MeshMessage(
    val id: String,
    val originDeviceId: String,
    val destinationUserId: String,
    val encryptedPayload: ByteArray,
    val timestamp: Long,
    val ttl: Int = 5,                // Time To Live (max hops)
    val hopCount: Int = 0,           // Current hop count
    val path: List<String> = emptyList() // Device IDs in path
) {
    fun incrementHop(relayDeviceId: String): MeshMessage {
        return copy(
            hopCount = hopCount + 1,
            path = path + relayDeviceId
        )
    }
    
    fun shouldRelay(): Boolean {
        return hopCount < ttl
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MeshMessage
        return id == other.id
    }
    
    override fun hashCode(): Int = id.hashCode()
}
```

### RoutingTable.kt

```kotlin
package com.meshcipher.data.bluetooth.routing

data class RoutingTable(
    val entries: Map<String, RouteEntry> = emptyMap()
) {
    fun addRoute(userId: String, via: String, hopCount: Int) {
        val current = entries[userId]
        if (current == null || current.hopCount > hopCount) {
            entries[userId] = RouteEntry(
                destinationUserId = userId,
                nextHopDeviceId = via,
                hopCount = hopCount,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }
    
    fun getRoute(userId: String): RouteEntry? {
        val entry = entries[userId]
        // Expire routes after 2 minutes
        if (entry != null && System.currentTimeMillis() - entry.lastUpdated > 120000) {
            entries.remove(userId)
            return null
        }
        return entry
    }
    
    fun removeStaleRoutes() {
        val now = System.currentTimeMillis()
        entries.entries.removeIf { (_, entry) ->
            now - entry.lastUpdated > 120000
        }
    }
}

data class RouteEntry(
    val destinationUserId: String,
    val nextHopDeviceId: String,
    val hopCount: Int,
    val lastUpdated: Long
)
```

---

## Step 3: BLE Advertisement Protocol

### Advertisement Packet Format

```
[Header: 2 bytes] [Device ID: 32 bytes] [User ID: 32 bytes] [Signature: 64 bytes]

Header:
- Byte 0: Protocol version (0x01)
- Byte 1: Message type (0x01 = BEACON, 0x02 = DATA)

Device ID: Our hardware device ID
User ID: Our cryptographic user ID
Signature: Ed25519 signature over (DeviceID + UserID + Timestamp)
```

### AdvertisementData.kt

```kotlin
package com.meshcipher.data.bluetooth

import java.nio.ByteBuffer
import java.security.MessageDigest

data class AdvertisementData(
    val protocolVersion: Byte = 0x01,
    val messageType: MessageType,
    val deviceId: String,
    val userId: String,
    val timestamp: Long,
    val signature: ByteArray
) {
    enum class MessageType(val value: Byte) {
        BEACON(0x01),
        DATA(0x02),
        ROUTE_UPDATE(0x03)
    }
    
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(130) // 2 + 32 + 32 + 64
        
        // Header
        buffer.put(protocolVersion)
        buffer.put(messageType.value)
        
        // Device ID (hash to 32 bytes)
        val deviceIdHash = MessageDigest.getInstance("SHA-256")
            .digest(deviceId.toByteArray())
        buffer.put(deviceIdHash)
        
        // User ID (hash to 32 bytes)
        val userIdHash = MessageDigest.getInstance("SHA-256")
            .digest(userId.toByteArray())
        buffer.put(userIdHash)
        
        // Signature
        buffer.put(signature)
        
        return buffer.array()
    }
    
    companion object {
        fun fromBytes(bytes: ByteArray): AdvertisementData? {
            if (bytes.size < 130) return null
            
            val buffer = ByteBuffer.wrap(bytes)
            
            val protocolVersion = buffer.get()
            val messageType = when (buffer.get()) {
                0x01.toByte() -> MessageType.BEACON
                0x02.toByte() -> MessageType.DATA
                0x03.toByte() -> MessageType.ROUTE_UPDATE
                else -> return null
            }
            
            val deviceIdHash = ByteArray(32)
            buffer.get(deviceIdHash)
            
            val userIdHash = ByteArray(32)
            buffer.get(userIdHash)
            
            val signature = ByteArray(64)
            buffer.get(signature)
            
            // Note: We can't reconstruct original IDs from hashes
            // This is for discovery only
            // Full handshake happens over GATT connection
            
            return AdvertisementData(
                protocolVersion = protocolVersion,
                messageType = messageType,
                deviceId = deviceIdHash.joinToString("") { "%02x".format(it) },
                userId = userIdHash.joinToString("") { "%02x".format(it) },
                timestamp = System.currentTimeMillis(),
                signature = signature
            )
        }
    }
}
```

---

## Step 4: BLE Manager

### BluetoothMeshManager.kt

```kotlin
package com.meshcipher.data.bluetooth

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.domain.model.MeshPeer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothMeshManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityManager: IdentityManager
) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    
    private val _discoveredPeers = MutableStateFlow<List<MeshPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<MeshPeer>> = _discoveredPeers.asStateFlow()
    
    companion object {
        // MeshCipher Service UUID
        val SERVICE_UUID: UUID = UUID.fromString("00001234-0000-1000-8000-00805F9B34FB")
        val CHARACTERISTIC_MESSAGE_UUID: UUID = UUID.fromString("00001235-0000-1000-8000-00805F9B34FB")
        val CHARACTERISTIC_ACK_UUID: UUID = UUID.fromString("00001236-0000-1000-8000-00805F9B34FB")
    }
    
    /**
     * Start advertising our presence
     */
    suspend fun startAdvertising(): Result<Unit> {
        if (!isBluetoothEnabled()) {
            return Result.failure(Exception("Bluetooth not enabled"))
        }
        
        val identity = identityManager.getIdentity()
            ?: return Result.failure(Exception("No identity"))
        
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            ?: return Result.failure(Exception("BLE advertising not supported"))
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .build()
        
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Timber.d("BLE advertising started")
            }
            
            override fun onStartFailure(errorCode: Int) {
                Timber.e("BLE advertising failed: $errorCode")
            }
        }
        
        try {
            advertiser?.startAdvertising(settings, data, callback)
            return Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start advertising")
            return Result.failure(e)
        }
    }
    
    /**
     * Stop advertising
     */
    fun stopAdvertising() {
        advertiser?.stopAdvertising(object : AdvertiseCallback() {})
        Timber.d("BLE advertising stopped")
    }
    
    /**
     * Start scanning for nearby peers
     */
    fun startScanning(): Result<Unit> {
        if (!isBluetoothEnabled()) {
            return Result.failure(Exception("Bluetooth not enabled"))
        }
        
        scanner = bluetoothAdapter?.bluetoothLeScanner
            ?: return Result.failure(Exception("BLE scanning not supported"))
        
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }
            
            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { handleScanResult(it) }
            }
            
            override fun onScanFailed(errorCode: Int) {
                Timber.e("BLE scan failed: $errorCode")
            }
        }
        
        try {
            scanner?.startScan(listOf(scanFilter), scanSettings, callback)
            Timber.d("BLE scanning started")
            return Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start scanning")
            return Result.failure(e)
        }
    }
    
    /**
     * Stop scanning
     */
    fun stopScanning() {
        scanner?.stopScan(object : ScanCallback() {})
        Timber.d("BLE scanning stopped")
    }
    
    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val rssi = result.rssi
        
        Timber.d("Discovered device: ${device.address}, RSSI: $rssi")
        
        // Extract service data
        val scanRecord = result.scanRecord ?: return
        val serviceData = scanRecord.getServiceData(ParcelUuid(SERVICE_UUID))
        
        // TODO: Parse advertisement data
        // TODO: Create MeshPeer
        // TODO: Add to discovered peers
        
        // For now, create basic peer
        val peer = MeshPeer(
            deviceId = device.address,
            userId = "unknown", // Will get from GATT connection
            displayName = null,
            bluetoothAddress = device.address,
            rssi = rssi,
            lastSeen = System.currentTimeMillis(),
            isContact = false,
            hopCount = 1
        )
        
        updateDiscoveredPeers(peer)
    }
    
    private fun updateDiscoveredPeers(newPeer: MeshPeer) {
        val current = _discoveredPeers.value.toMutableList()
        val existing = current.find { it.bluetoothAddress == newPeer.bluetoothAddress }
        
        if (existing != null) {
            current.remove(existing)
        }
        
        current.add(newPeer)
        _discoveredPeers.value = current
    }
    
    private fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
}
```

---

## Step 5: GATT Server (for receiving connections)

### GattServerManager.kt

```kotlin
package com.meshcipher.data.bluetooth

import android.bluetooth.*
import android.content.Context
import com.meshcipher.domain.model.MeshMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GattServerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private var gattServer: BluetoothGattServer? = null
    
    private val _receivedMessages = MutableSharedFlow<MeshMessage>()
    val receivedMessages: SharedFlow<MeshMessage> = _receivedMessages.asSharedFlow()
    
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    
    fun startGattServer(): Result<Unit> {
        val service = BluetoothGattService(
            BluetoothMeshManager.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        
        // Message characteristic (write)
        val messageCharacteristic = BluetoothGattCharacteristic(
            BluetoothMeshManager.CHARACTERISTIC_MESSAGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(messageCharacteristic)
        
        // ACK characteristic (read/notify)
        val ackCharacteristic = BluetoothGattCharacteristic(
            BluetoothMeshManager.CHARACTERISTIC_ACK_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or 
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(ackCharacteristic)
        
        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int
            ) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Timber.d("Device connected: ${device.address}")
                        connectedDevices.add(device)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Timber.d("Device disconnected: ${device.address}")
                        connectedDevices.remove(device)
                    }
                }
            }
            
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                when (characteristic.uuid) {
                    BluetoothMeshManager.CHARACTERISTIC_MESSAGE_UUID -> {
                        handleMessageReceived(device, value)
                        
                        if (responseNeeded) {
                            gattServer?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                null
                            )
                        }
                    }
                }
            }
        }
        
        try {
            gattServer = bluetoothManager.openGattServer(context, callback)
            gattServer?.addService(service)
            Timber.d("GATT server started")
            return Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start GATT server")
            return Result.failure(e)
        }
    }
    
    fun stopGattServer() {
        connectedDevices.forEach { device ->
            gattServer?.cancelConnection(device)
        }
        gattServer?.close()
        gattServer = null
        Timber.d("GATT server stopped")
    }
    
    private fun handleMessageReceived(device: BluetoothDevice, data: ByteArray) {
        try {
            // Parse MeshMessage from bytes
            val message = parseMeshMessage(data)
            
            kotlinx.coroutines.GlobalScope.launch {
                _receivedMessages.emit(message)
            }
            
            Timber.d("Received mesh message from ${device.address}: ${message.id}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse mesh message")
        }
    }
    
    private fun parseMeshMessage(data: ByteArray): MeshMessage {
        // TODO: Implement proper deserialization
        throw NotImplementedError("Message parsing not implemented")
    }
}
```

---

## Step 6: Bluetooth Transport

### BluetoothMeshTransport.kt

```kotlin
package com.meshcipher.data.transport

import com.meshcipher.data.bluetooth.BluetoothMeshManager
import com.meshcipher.data.bluetooth.GattServerManager
import com.meshcipher.data.bluetooth.routing.MeshRouter
import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.model.MeshMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothMeshTransport @Inject constructor(
    private val bluetoothManager: BluetoothMeshManager,
    private val gattServerManager: GattServerManager,
    private val meshRouter: MeshRouter
) : Transport {
    
    override suspend fun send(
        message: EncryptedMessage,
        recipient: Contact
    ): SendResult {
        if (!isAvailable()) {
            return SendResult.Failed("Bluetooth not available")
        }
        
        // Convert to MeshMessage
        val meshMessage = MeshMessage(
            id = message.id,
            originDeviceId = "my-device-id", // TODO: Get actual device ID
            destinationUserId = recipient.userId,
            encryptedPayload = message.ciphertext,
            timestamp = message.timestamp,
            ttl = 5,
            hopCount = 0
        )
        
        // Route message
        return try {
            meshRouter.routeMessage(meshMessage)
            SendResult.Success("Bluetooth Mesh")
        } catch (e: Exception) {
            Timber.e(e, "Failed to route mesh message")
            SendResult.Failed(e.message ?: "Routing failed")
        }
    }
    
    override suspend fun receive(): Flow<EncryptedMessage> {
        return gattServerManager.receivedMessages.map { meshMessage ->
            EncryptedMessage(
                id = meshMessage.id,
                senderId = meshMessage.originDeviceId,
                recipientId = "me",
                ciphertext = meshMessage.encryptedPayload,
                timestamp = meshMessage.timestamp
            )
        }
    }
    
    override fun isAvailable(): Boolean {
        return bluetoothManager.discoveredPeers.value.isNotEmpty()
    }
    
    override suspend fun isContactReachable(contact: Contact): Boolean {
        // Check if contact is in discovered peers or reachable via routing table
        return meshRouter.canReach(contact.userId)
    }
}
```

---

## Step 7: Mesh Router

### MeshRouter.kt

```kotlin
package com.meshcipher.data.bluetooth.routing

import com.meshcipher.data.bluetooth.BluetoothMeshManager
import com.meshcipher.data.bluetooth.GattClientManager
import com.meshcipher.domain.model.MeshMessage
import com.meshcipher.domain.model.MeshPeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshRouter @Inject constructor(
    private val bluetoothManager: BluetoothMeshManager,
    private val gattClientManager: GattClientManager
) {
    private val routingTable = RoutingTable()
    private val seenMessages = mutableSetOf<String>()
    
    suspend fun routeMessage(message: MeshMessage): Result<Unit> = withContext(Dispatchers.IO) {
        // Check if already seen (prevent loops)
        if (seenMessages.contains(message.id)) {
            Timber.d("Message ${message.id} already seen, dropping")
            return@withContext Result.success(Unit)
        }
        seenMessages.add(message.id)
        
        // Check TTL
        if (!message.shouldRelay()) {
            Timber.d("Message ${message.id} TTL expired")
            return@withContext Result.failure(Exception("TTL expired"))
        }
        
        // Find route
        val route = routingTable.getRoute(message.destinationUserId)
        
        if (route != null) {
            // We have a route, forward to next hop
            val nextHop = findPeer(route.nextHopDeviceId)
            if (nextHop != null) {
                sendToPeer(nextHop, message.incrementHop(getMyDeviceId()))
                return@withContext Result.success(Unit)
            }
        }
        
        // No route found, try flooding to all neighbors
        Timber.d("No route for ${message.destinationUserId}, flooding")
        val neighbors = getDirectNeighbors()
        
        if (neighbors.isEmpty()) {
            return@withContext Result.failure(Exception("No neighbors available"))
        }
        
        neighbors.forEach { neighbor ->
            sendToPeer(neighbor, message.incrementHop(getMyDeviceId()))
        }
        
        Result.success(Unit)
    }
    
    fun canReach(userId: String): Boolean {
        return routingTable.getRoute(userId) != null
    }
    
    fun updateRoute(userId: String, viaDeviceId: String, hopCount: Int) {
        routingTable.addRoute(userId, viaDeviceId, hopCount)
        Timber.d("Route added: $userId via $viaDeviceId ($hopCount hops)")
    }
    
    private fun getDirectNeighbors(): List<MeshPeer> {
        return bluetoothManager.discoveredPeers.value.filter { it.isInRange }
    }
    
    private fun findPeer(deviceId: String): MeshPeer? {
        return bluetoothManager.discoveredPeers.value
            .find { it.deviceId == deviceId }
    }
    
    private suspend fun sendToPeer(peer: MeshPeer, message: MeshMessage) {
        try {
            gattClientManager.sendMessage(peer.bluetoothAddress, message)
            Timber.d("Message ${message.id} sent to ${peer.deviceId}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to send message to ${peer.deviceId}")
        }
    }
    
    private fun getMyDeviceId(): String {
        // TODO: Get actual device ID
        return "my-device-id"
    }
}
```

---

## Step 8: Update Transport Manager

### TransportManager.kt (Updated)

```kotlin
@Singleton
class TransportManager @Inject constructor(
    private val internetTransport: InternetTransport,
    private val torTransport: TorTransport,
    private val bluetoothMeshTransport: BluetoothMeshTransport,
    private val connectionModeProvider: ConnectionModeProvider
) {
    
    suspend fun sendMessage(
        message: EncryptedMessage,
        recipient: Contact
    ): SendResult {
        val mode = connectionModeProvider.connectionMode.first()
        
        // Try transports in priority order
        val transports = when (mode) {
            ConnectionMode.DIRECT -> listOf(
                internetTransport,
                bluetoothMeshTransport // Fallback
            )
            ConnectionMode.TOR_RELAY -> listOf(
                torTransport,
                bluetoothMeshTransport // Fallback
            )
            ConnectionMode.P2P_ONLY -> listOf(
                bluetoothMeshTransport
            )
        }
        
        for (transport in transports) {
            if (!transport.isAvailable()) continue
            
            try {
                val result = transport.send(message, recipient)
                if (result is SendResult.Success || result is SendResult.Queued) {
                    return result
                }
            } catch (e: Exception) {
                Timber.w(e, "Transport ${transport::class.simpleName} failed")
                continue
            }
        }
        
        return SendResult.Failed("All transports failed")
    }
}
```

---

## Step 9: Mesh Visualization

### MeshNetworkScreen.kt

```kotlin
package com.meshcipher.presentation.mesh

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshNetworkScreen(
    onBackClick: () -> Unit,
    viewModel: MeshNetworkViewModel = hiltViewModel()
) {
    val peers by viewModel.discoveredPeers.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mesh Network") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Nearby Devices: ${peers.size}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Text(
                        text = if (isScanning) "Scanning..." else "Scan stopped",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Network Visualization
            MeshNetworkVisualization(
                peers = peers,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp)
            )
            
            // Peer List
            Text(
                text = "Discovered Peers",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            peers.forEach { peer ->
                PeerListItem(peer = peer)
            }
        }
    }
}

@Composable
fun MeshNetworkVisualization(
    peers: List<MeshPeer>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = size.minDimension / 3
        
        // Draw center node (this device)
        drawCircle(
            color = Color.Blue,
            radius = 20f,
            center = Offset(centerX, centerY)
        )
        
        // Draw peers in a circle around center
        peers.forEachIndexed { index, peer ->
            val angle = (2 * Math.PI * index / peers.size).toFloat()
            val peerX = centerX + radius * cos(angle)
            val peerY = centerY + radius * sin(angle)
            
            // Draw connection line
            drawLine(
                color = when (peer.signalStrength) {
                    SignalStrength.EXCELLENT -> Color.Green
                    SignalStrength.GOOD -> Color.Yellow
                    SignalStrength.FAIR -> Color.Orange
                    SignalStrength.WEAK -> Color.Red
                },
                start = Offset(centerX, centerY),
                end = Offset(peerX, peerY),
                strokeWidth = 2f
            )
            
            // Draw peer node
            drawCircle(
                color = if (peer.isContact) Color.Green else Color.Gray,
                radius = 15f,
                center = Offset(peerX, peerY)
            )
            
            // Draw hop count indicator
            if (peer.hopCount > 1) {
                drawCircle(
                    color = Color.Red,
                    radius = 8f,
                    center = Offset(peerX, peerY),
                    style = Stroke(width = 2f)
                )
            }
        }
    }
}

@Composable
fun PeerListItem(peer: MeshPeer) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName ?: peer.userId.take(16) + "...",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${peer.hopCount} hop${if (peer.hopCount != 1) "s" else ""} • RSSI: ${peer.rssi}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Signal strength indicator
            Icon(
                when (peer.signalStrength) {
                    SignalStrength.EXCELLENT -> Icons.Default.SignalCellular4Bar
                    SignalStrength.GOOD -> Icons.Default.SignalCellular3Bar
                    SignalStrength.FAIR -> Icons.Default.SignalCellular2Bar
                    SignalStrength.WEAK -> Icons.Default.SignalCellular1Bar
                },
                contentDescription = "Signal strength",
                tint = when (peer.signalStrength) {
                    SignalStrength.EXCELLENT -> Color.Green
                    SignalStrength.GOOD -> Color.Yellow
                    SignalStrength.FAIR -> Color.Orange
                    SignalStrength.WEAK -> Color.Red
                }
            )
        }
    }
}
```

---

## Step 10: Bluetooth Permissions Runtime Request

### PermissionUtils.kt

```kotlin
package com.meshcipher.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionUtils {
    
    fun getRequiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }
    
    fun hasBluetoothPermissions(context: Context): Boolean {
        return getRequiredBluetoothPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == 
                PackageManager.PERMISSION_GRANTED
        }
    }
}
```

---

## Phase 4 Checklist

- [ ] Bluetooth permissions added to manifest
- [ ] Runtime permission requests implemented
- [ ] MeshPeer domain model created
- [ ] MeshMessage domain model created
- [ ] RoutingTable implementation
- [ ] BluetoothMeshManager created
- [ ] BLE advertising working
- [ ] BLE scanning working
- [ ] GattServerManager created
- [ ] GattClientManager created
- [ ] BluetoothMeshTransport implemented
- [ ] MeshRouter with routing logic
- [ ] TransportManager updated for mesh fallback
- [ ] MeshNetworkScreen with visualization
- [ ] MeshNetworkViewModel
- [ ] PermissionUtils created
- [ ] Can discover nearby peers
- [ ] Can send direct peer messages
- [ ] Multi-hop routing working
- [ ] Messages relay correctly
- [ ] Tests written
- [ ] Battery optimization done

---

## Testing Guide

### Manual Testing:

**1. Single Device Test:**
- Enable Bluetooth
- Grant permissions
- Start advertising
- Start scanning
- Should see own device

**2. Two Device Test:**
- Both devices: Enable Bluetooth, grant permissions
- Both: Start advertising + scanning
- Should discover each other
- Send message from Device A
- Should receive on Device B

**3. Three Device Test (Multi-hop):**
- Device A ←BLE→ Device B ←BLE→ Device C
- A and C out of range of each other
- Send from A to C
- B should relay message
- C receives message

**4. Battery Test:**
- Run for 1 hour with Bluetooth active
- Check battery drain
- Should be <10% per hour

---

## Next Phase

**Phase 5: WiFi Direct** - See `Phase_5_WiFi_Direct.md`

This will add:
- WiFi P2P connections
- Medium-range offline (100m)
- Higher bandwidth than Bluetooth
- Group formation