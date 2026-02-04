# MeshCipher - Phase 5: WiFi Direct P2P
## Weeks 19-21: Medium-Range Offline Communication

---

## Phase 5 Overview

**Goals:**
- Implement WiFi Direct (WiFi P2P) for device-to-device communication
- Medium-range connectivity (100m vs 10m Bluetooth)
- Higher bandwidth for large files and future video calls
- Peer discovery and group formation
- Works completely offline
- Complements Bluetooth mesh for better performance

**Deliverables:**
- WiFi Direct peer discovery
- Group owner negotiation
- Socket-based data transfer
- File transfer optimization
- Automatic transport selection
- Network topology management
- WiFi Direct + Bluetooth hybrid mode
- Works offline with better range than Bluetooth

**Prerequisites:**
- ✅ Phase 4 complete (Bluetooth mesh working)
- ✅ Phase 4.5 complete (Media sharing working)
- ✅ All transports operational

---

## WiFi Direct vs Bluetooth

**Comparison:**

| Feature | Bluetooth LE | WiFi Direct |
|---------|--------------|-------------|
| **Range** | ~10m indoor | ~100m indoor |
| **Bandwidth** | ~1 Mbps | ~250 Mbps |
| **Power** | Very low | Higher |
| **Latency** | ~5-10ms | ~2-5ms |
| **Setup Time** | ~3-5s | ~5-10s |
| **Concurrent Connections** | ~7 devices | ~8 devices |
| **Use Case** | Always-on mesh | High-bandwidth transfers |

**When to Use Each:**

- **Bluetooth:** Background presence, low-power mesh, voice messages
- **WiFi Direct:** Large files, video calls, high-priority transfers
- **Hybrid:** Use both simultaneously for optimal performance

---

## Architecture

```
WiFi Direct Network Topology:

Group Owner (GO) - Acts as access point
    ↓
    ├─ Client 1 (receives IP via DHCP)
    ├─ Client 2
    └─ Client 3

All devices can communicate through GO:
Client 1 ↔ GO ↔ Client 2

Or direct sockets:
Client 1 ↔ Client 2 (if GO allows)

Multi-hop via multiple groups:
Group A (Device A is GO) ↔ Device B (member of both) ↔ Group C (Device C is GO)
```

**Connection Flow:**
```
1. Discovery Phase
   - Enable WiFi Direct
   - Scan for peers
   - Exchange service info

2. Connection Phase
   - Negotiate Group Owner
   - DHCP assigns IPs
   - Establish socket connection

3. Data Transfer Phase
   - TCP socket for reliable transfer
   - Chunked data with progress
   - Acknowledge receipt

4. Disconnection Phase
   - Close sockets
   - Maintain peer info
   - Re-discover if needed
```

---

## Step 1: Add Permissions

### AndroidManifest.xml (Updated)

```xml
<manifest>
    <!-- Existing permissions -->
    
    <!-- WiFi Direct permissions -->
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    
    <!-- Android 13+ WiFi permissions -->
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
        android:usesPermissionFlags="neverForLocation" />
    
    <uses-feature android:name="android.hardware.wifi.direct" android:required="false" />
</manifest>
```

---

## Step 2: Domain Models

### WifiDirectPeer.kt

```kotlin
package com.meshcipher.domain.model

import android.net.wifi.p2p.WifiP2pDevice

data class WifiDirectPeer(
    val deviceAddress: String,        // MAC address
    val deviceName: String,
    val userId: String?,               // Our cryptographic user ID
    val status: ConnectionStatus,
    val isGroupOwner: Boolean = false,
    val groupOwnerAddress: String? = null,
    val lastSeen: Long = System.currentTimeMillis()
) {
    enum class ConnectionStatus {
        AVAILABLE,
        INVITED,
        CONNECTED,
        FAILED,
        UNAVAILABLE
    }
    
    companion object {
        fun fromWifiP2pDevice(device: WifiP2pDevice, userId: String? = null): WifiDirectPeer {
            return WifiDirectPeer(
                deviceAddress = device.deviceAddress,
                deviceName = device.deviceName,
                userId = userId,
                status = when (device.status) {
                    WifiP2pDevice.AVAILABLE -> ConnectionStatus.AVAILABLE
                    WifiP2pDevice.INVITED -> ConnectionStatus.INVITED
                    WifiP2pDevice.CONNECTED -> ConnectionStatus.CONNECTED
                    WifiP2pDevice.FAILED -> ConnectionStatus.FAILED
                    WifiP2pDevice.UNAVAILABLE -> ConnectionStatus.UNAVAILABLE
                    else -> ConnectionStatus.UNAVAILABLE
                }
            )
        }
    }
}
```

### WifiDirectGroup.kt

```kotlin
package com.meshcipher.domain.model

data class WifiDirectGroup(
    val networkName: String,
    val passphrase: String,
    val groupOwnerAddress: String,
    val isGroupOwner: Boolean,
    val clients: List<WifiDirectPeer>,
    val createdAt: Long = System.currentTimeMillis()
)
```

### WifiDirectMessage.kt

```kotlin
package com.meshcipher.domain.model

import java.io.Serializable

sealed class WifiDirectMessage : Serializable {
    data class TextMessage(
        val messageId: String,
        val senderId: String,
        val recipientId: String,
        val encryptedContent: ByteArray,
        val timestamp: Long
    ) : WifiDirectMessage()
    
    data class FileTransfer(
        val fileId: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val chunks: Int,
        val senderId: String,
        val recipientId: String
    ) : WifiDirectMessage()
    
    data class FileChunk(
        val fileId: String,
        val chunkIndex: Int,
        val totalChunks: Int,
        val data: ByteArray
    ) : WifiDirectMessage()
    
    data class Acknowledgment(
        val messageId: String,
        val success: Boolean
    ) : WifiDirectMessage()
}
```

---

## Step 3: WiFi Direct Manager

### WifiDirectManager.kt

```kotlin
package com.meshcipher.data.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import android.os.Looper
import com.meshcipher.domain.model.WifiDirectPeer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiDirectManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val wifiP2pManager: WifiP2pManager? = 
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    
    private var channel: WifiP2pManager.Channel? = null
    
    private val _discoveredPeers = MutableStateFlow<List<WifiDirectPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<WifiDirectPeer>> = _discoveredPeers.asStateFlow()
    
    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()
    
    private val _groupInfo = MutableStateFlow<WifiP2pGroup?>(null)
    val groupInfo: StateFlow<WifiP2pGroup?> = _groupInfo.asStateFlow()
    
    /**
     * Initialize WiFi Direct
     */
    fun initialize(): Result<Unit> {
        return try {
            channel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)
            
            if (channel == null) {
                return Result.failure(Exception("WiFi Direct not supported"))
            }
            
            registerReceiver()
            Timber.d("WiFi Direct initialized")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize WiFi Direct")
            Result.failure(e)
        }
    }
    
    /**
     * Start discovering peers
     */
    fun startDiscovery(): Result<Unit> {
        if (channel == null) {
            return Result.failure(Exception("WiFi Direct not initialized"))
        }
        
        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("Peer discovery started")
            }
            
            override fun onFailure(reason: Int) {
                Timber.e("Peer discovery failed: ${getFailureReason(reason)}")
            }
        })
        
        return Result.success(Unit)
    }
    
    /**
     * Stop discovering peers
     */
    fun stopDiscovery() {
        wifiP2pManager?.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("Peer discovery stopped")
            }
            
            override fun onFailure(reason: Int) {
                Timber.w("Failed to stop discovery: ${getFailureReason(reason)}")
            }
        })
    }
    
    /**
     * Connect to a peer
     */
    fun connect(peer: WifiDirectPeer): Result<Unit> {
        if (channel == null) {
            return Result.failure(Exception("WiFi Direct not initialized"))
        }
        
        val config = WifiP2pConfig().apply {
            deviceAddress = peer.deviceAddress
            wps.setup = WpsInfo.PBC // Push Button Configuration
            groupOwnerIntent = 0 // Let system decide who becomes GO
        }
        
        wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("Connection initiated to ${peer.deviceName}")
            }
            
            override fun onFailure(reason: Int) {
                Timber.e("Connection failed: ${getFailureReason(reason)}")
            }
        })
        
        return Result.success(Unit)
    }
    
    /**
     * Disconnect from current group
     */
    fun disconnect() {
        wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("Disconnected from WiFi Direct group")
                _connectionInfo.value = null
                _groupInfo.value = null
            }
            
            override fun onFailure(reason: Int) {
                Timber.w("Disconnect failed: ${getFailureReason(reason)}")
            }
        })
    }
    
    /**
     * Create a WiFi Direct group (become Group Owner)
     */
    fun createGroup(): Result<Unit> {
        if (channel == null) {
            return Result.failure(Exception("WiFi Direct not initialized"))
        }
        
        wifiP2pManager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("WiFi Direct group created")
                requestGroupInfo()
            }
            
            override fun onFailure(reason: Int) {
                Timber.e("Group creation failed: ${getFailureReason(reason)}")
            }
        })
        
        return Result.success(Unit)
    }
    
    /**
     * Request current connection info
     */
    private fun requestConnectionInfo() {
        wifiP2pManager?.requestConnectionInfo(channel) { info ->
            _connectionInfo.value = info
            Timber.d("Connection info: GO=${info.groupOwnerAddress}, isGO=${info.isGroupOwner}")
        }
    }
    
    /**
     * Request current group info
     */
    private fun requestGroupInfo() {
        wifiP2pManager?.requestGroupInfo(channel) { group ->
            _groupInfo.value = group
            Timber.d("Group info: ${group?.networkName}, clients=${group?.clientList?.size}")
        }
    }
    
    /**
     * Register broadcast receiver for WiFi Direct events
     */
    private fun registerReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        
        context.registerReceiver(wifiDirectReceiver, intentFilter)
    }
    
    /**
     * Broadcast receiver for WiFi Direct events
     */
    private val wifiDirectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Timber.d("WiFi P2P state: ${if (enabled) "ENABLED" else "DISABLED"}")
                }
                
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    // Request peer list
                    wifiP2pManager?.requestPeers(channel) { peerList ->
                        val peers = peerList.deviceList.map { device ->
                            WifiDirectPeer.fromWifiP2pDevice(device)
                        }
                        _discoveredPeers.value = peers
                        Timber.d("Discovered ${peers.size} WiFi Direct peers")
                    }
                }
                
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(
                        WifiP2pManager.EXTRA_NETWORK_INFO
                    )
                    
                    if (networkInfo?.isConnected == true) {
                        requestConnectionInfo()
                        requestGroupInfo()
                    } else {
                        _connectionInfo.value = null
                        _groupInfo.value = null
                    }
                }
                
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = intent.getParcelableExtra<WifiP2pDevice>(
                        WifiP2pManager.EXTRA_WIFI_P2P_DEVICE
                    )
                    Timber.d("This device: ${device?.deviceName}, status=${device?.status}")
                }
            }
        }
    }
    
    /**
     * Check if WiFi Direct is supported
     */
    fun isSupported(): Boolean {
        return wifiP2pManager != null && 
               context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT)
    }
    
    /**
     * Cleanup
     */
    fun cleanup() {
        try {
            context.unregisterReceiver(wifiDirectReceiver)
            disconnect()
            channel?.close()
        } catch (e: Exception) {
            Timber.e(e, "Cleanup failed")
        }
    }
    
    private fun getFailureReason(reason: Int): String {
        return when (reason) {
            WifiP2pManager.ERROR -> "ERROR"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "BUSY"
            else -> "UNKNOWN ($reason)"
        }
    }
}
```

---

## Step 4: Socket Communication

### WifiDirectSocketManager.kt

```kotlin
package com.meshcipher.data.wifidirect

import com.meshcipher.domain.model.WifiDirectMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiDirectSocketManager @Inject constructor() {
    
    companion object {
        private const val SERVER_PORT = 8988
        private const val SOCKET_TIMEOUT = 10000 // 10 seconds
        private const val BUFFER_SIZE = 64 * 1024 // 64KB
    }
    
    private val _receivedMessages = MutableSharedFlow<WifiDirectMessage>()
    val receivedMessages: SharedFlow<WifiDirectMessage> = _receivedMessages.asSharedFlow()
    
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var serverJob: Job? = null
    
    /**
     * Start server socket (for Group Owner)
     */
    fun startServer(scope: CoroutineScope) {
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(SERVER_PORT).apply {
                    soTimeout = SOCKET_TIMEOUT
                    reuseAddress = true
                }
                
                Timber.d("Server socket started on port $SERVER_PORT")
                
                while (isActive) {
                    try {
                        val client = serverSocket?.accept()
                        client?.let {
                            Timber.d("Client connected: ${it.inetAddress.hostAddress}")
                            handleClient(it, scope)
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Timber.e(e, "Error accepting client")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Server socket error")
            } finally {
                stopServer()
            }
        }
    }
    
    /**
     * Stop server socket
     */
    fun stopServer() {
        serverJob?.cancel()
        serverSocket?.close()
        serverSocket = null
        Timber.d("Server socket stopped")
    }
    
    /**
     * Connect to server as client
     */
    suspend fun connectToServer(
        hostAddress: String,
        onConnected: () -> Unit = {},
        onDisconnected: () -> Unit = {}
    ): Result<Socket> = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            socket.bind(null)
            socket.connect(InetSocketAddress(hostAddress, SERVER_PORT), SOCKET_TIMEOUT)
            
            clientSocket = socket
            Timber.d("Connected to server: $hostAddress")
            onConnected()
            
            Result.success(socket)
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to server")
            onDisconnected()
            Result.failure(e)
        }
    }
    
    /**
     * Send message through socket
     */
    suspend fun sendMessage(
        socket: Socket,
        message: WifiDirectMessage
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val outputStream = socket.getOutputStream()
            val objectOutputStream = ObjectOutputStream(outputStream)
            
            objectOutputStream.writeObject(message)
            objectOutputStream.flush()
            
            Timber.d("Message sent: ${message::class.simpleName}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send message")
            Result.failure(e)
        }
    }
    
    /**
     * Send file in chunks
     */
    suspend fun sendFile(
        socket: Socket,
        file: File,
        fileId: String,
        recipientId: String,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val fileSize = file.length()
            val totalChunks = ((fileSize + BUFFER_SIZE - 1) / BUFFER_SIZE).toInt()
            
            // Send file metadata
            val metadata = WifiDirectMessage.FileTransfer(
                fileId = fileId,
                fileName = file.name,
                fileSize = fileSize,
                mimeType = getMimeType(file),
                chunks = totalChunks,
                senderId = "me", // TODO: Get actual user ID
                recipientId = recipientId
            )
            sendMessage(socket, metadata).getOrThrow()
            
            // Send chunks
            file.inputStream().buffered().use { input ->
                var chunkIndex = 0
                val buffer = ByteArray(BUFFER_SIZE)
                
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    
                    val chunkData = buffer.copyOf(bytesRead)
                    val chunk = WifiDirectMessage.FileChunk(
                        fileId = fileId,
                        chunkIndex = chunkIndex,
                        totalChunks = totalChunks,
                        data = chunkData
                    )
                    
                    sendMessage(socket, chunk).getOrThrow()
                    
                    chunkIndex++
                    onProgress(chunkIndex.toFloat() / totalChunks)
                }
            }
            
            Timber.d("File sent successfully: ${file.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send file")
            Result.failure(e)
        }
    }
    
    /**
     * Handle incoming client connection
     */
    private fun handleClient(client: Socket, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                val inputStream = client.getInputStream()
                val objectInputStream = ObjectInputStream(inputStream)
                
                while (isActive && !client.isClosed) {
                    try {
                        val message = objectInputStream.readObject() as WifiDirectMessage
                        _receivedMessages.emit(message)
                        Timber.d("Message received: ${message::class.simpleName}")
                    } catch (e: EOFException) {
                        break
                    } catch (e: Exception) {
                        Timber.e(e, "Error reading message")
                        break
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Client handling error")
            } finally {
                client.close()
                Timber.d("Client disconnected")
            }
        }
    }
    
    /**
     * Disconnect client socket
     */
    fun disconnectClient() {
        clientSocket?.close()
        clientSocket = null
        Timber.d("Client socket closed")
    }
    
    /**
     * Cleanup all sockets
     */
    fun cleanup() {
        stopServer()
        disconnectClient()
    }
    
    private fun getMimeType(file: File): String {
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
    }
}
```

---

## Step 5: WiFi Direct Transport

### WifiDirectTransport.kt

```kotlin
package com.meshcipher.data.transport

import com.meshcipher.data.wifidirect.WifiDirectManager
import com.meshcipher.data.wifidirect.WifiDirectSocketManager
import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.model.EncryptedMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiDirectTransport @Inject constructor(
    private val wifiDirectManager: WifiDirectManager,
    private val socketManager: WifiDirectSocketManager
) : Transport {
    
    override suspend fun send(
        message: EncryptedMessage,
        recipient: Contact
    ): SendResult {
        if (!isAvailable()) {
            return SendResult.Failed("WiFi Direct not available")
        }
        
        return try {
            val connectionInfo = wifiDirectManager.connectionInfo.value
                ?: return SendResult.Failed("Not connected to WiFi Direct group")
            
            // Get socket
            val socket = if (connectionInfo.isGroupOwner) {
                // As GO, we need the client's socket
                // This is simplified - in reality, maintain socket map by device
                socketManager.clientSocket
            } else {
                // As client, connect to GO
                val connectResult = socketManager.connectToServer(
                    connectionInfo.groupOwnerAddress.hostAddress
                )
                connectResult.getOrNull()
            } ?: return SendResult.Failed("No socket available")
            
            // Convert to WifiDirectMessage
            val wifiMessage = com.meshcipher.domain.model.WifiDirectMessage.TextMessage(
                messageId = message.id,
                senderId = message.senderId,
                recipientId = message.recipientId,
                encryptedContent = message.ciphertext,
                timestamp = message.timestamp
            )
            
            // Send
            socketManager.sendMessage(socket, wifiMessage)
            
            Timber.d("Message sent via WiFi Direct")
            SendResult.Success("WiFi Direct")
        } catch (e: Exception) {
            Timber.e(e, "Failed to send via WiFi Direct")
            SendResult.Failed(e.message ?: "WiFi Direct send failed")
        }
    }
    
    override suspend fun receive(): Flow<EncryptedMessage> {
        return socketManager.receivedMessages
            .map { wifiMessage ->
                when (wifiMessage) {
                    is com.meshcipher.domain.model.WifiDirectMessage.TextMessage -> {
                        EncryptedMessage(
                            id = wifiMessage.messageId,
                            senderId = wifiMessage.senderId,
                            recipientId = wifiMessage.recipientId,
                            ciphertext = wifiMessage.encryptedContent,
                            timestamp = wifiMessage.timestamp
                        )
                    }
                    else -> null
                }
            }
            .filterNotNull()
    }
    
    override fun isAvailable(): Boolean {
        return wifiDirectManager.isSupported() && 
               wifiDirectManager.connectionInfo.value != null
    }
    
    override suspend fun isContactReachable(contact: Contact): Boolean {
        // Check if contact is in current WiFi Direct group
        val peers = wifiDirectManager.discoveredPeers.value
        return peers.any { it.userId == contact.userId }
    }
}
```

---

## Step 6: Update Transport Manager

### TransportManager.kt (Updated)

```kotlin
@Singleton
class TransportManager @Inject constructor(
    private val internetTransport: InternetTransport,
    private val torTransport: TorTransport,
    private val bluetoothMeshTransport: BluetoothMeshTransport,
    private val wifiDirectTransport: WifiDirectTransport,
    private val connectionModeProvider: ConnectionModeProvider
) {
    
    suspend fun sendMessage(
        message: EncryptedMessage,
        recipient: Contact
    ): SendResult {
        val mode = connectionModeProvider.connectionMode.first()
        
        // Transport priority by mode and availability
        val transports = when (mode) {
            ConnectionMode.DIRECT -> listOf(
                internetTransport,
                wifiDirectTransport,        // NEW: Try WiFi Direct before Bluetooth
                bluetoothMeshTransport
            )
            ConnectionMode.TOR_RELAY -> listOf(
                torTransport,
                wifiDirectTransport,
                bluetoothMeshTransport
            )
            ConnectionMode.P2P_ONLY -> listOf(
                wifiDirectTransport,        // NEW: WiFi Direct for P2P
                bluetoothMeshTransport
            )
        }
        
        // Try transports in priority order
        for (transport in transports) {
            if (!transport.isAvailable()) continue
            
            try {
                val result = transport.send(message, recipient)
                if (result is SendResult.Success || result is SendResult.Queued) {
                    Timber.d("Message sent via ${transport::class.simpleName}")
                    return result
                }
            } catch (e: Exception) {
                Timber.w(e, "Transport ${transport::class.simpleName} failed")
                continue
            }
        }
        
        return SendResult.Failed("All transports failed")
    }
    
    /**
     * Get best available transport for large file transfer
     */
    suspend fun getBestTransportForFile(fileSizeMB: Long): Transport? {
        return when {
            // Large files (>50MB): Prefer WiFi Direct
            fileSizeMB > 50 && wifiDirectTransport.isAvailable() -> wifiDirectTransport
            
            // Medium files (10-50MB): WiFi Direct or Internet
            fileSizeMB > 10 -> {
                when {
                    wifiDirectTransport.isAvailable() -> wifiDirectTransport
                    internetTransport.isAvailable() -> internetTransport
                    else -> bluetoothMeshTransport
                }
            }
            
            // Small files: Any transport
            else -> {
                transports.firstOrNull { it.isAvailable() }
            }
        }
    }
}
```

---

## Step 7: WiFi Direct UI

### WifiDirectScreen.kt

```kotlin
package com.meshcipher.presentation.wifidirect

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiDirectScreen(
    onBackClick: () -> Unit,
    viewModel: WifiDirectViewModel = hiltViewModel()
) {
    val peers by viewModel.discoveredPeers.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val connectionInfo by viewModel.connectionInfo.collectAsState()
    val groupInfo by viewModel.groupInfo.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi Direct") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleDiscovery() }) {
                        Icon(
                            if (isDiscovering) Icons.Default.Stop else Icons.Default.Refresh,
                            contentDescription = if (isDiscovering) "Stop" else "Discover"
                        )
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
            // Connection status
            ConnectionStatusCard(
                connectionInfo = connectionInfo,
                groupInfo = groupInfo,
                onCreateGroup = { viewModel.createGroup() },
                onDisconnect = { viewModel.disconnect() }
            )
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // Discovered peers
            Text(
                text = "Nearby Devices (${peers.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            
            if (isDiscovering) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
            
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(peers) { peer ->
                    PeerCard(
                        peer = peer,
                        onConnect = { viewModel.connectToPeer(peer) }
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(
    connectionInfo: android.net.wifi.p2p.WifiP2pInfo?,
    groupInfo: android.net.wifi.p2p.WifiP2pGroup?,
    onCreateGroup: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Connection Status",
                style = MaterialTheme.typography.titleMedium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            when {
                connectionInfo != null -> {
                    // Connected
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (connectionInfo.isGroupOwner) 
                                    "Group Owner" 
                                else 
                                    "Client",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Connected to ${connectionInfo.groupOwnerAddress.hostAddress}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    if (groupInfo != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Network: ${groupInfo.networkName}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Clients: ${groupInfo.clientList.size}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Disconnect")
                    }
                }
                else -> {
                    // Not connected
                    Text(
                        text = "Not connected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onCreateGroup,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Group")
                    }
                }
            }
        }
    }
}

@Composable
fun PeerCard(
    peer: com.meshcipher.domain.model.WifiDirectPeer,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.deviceName,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = peer.deviceAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Status badge
                Badge(
                    containerColor = when (peer.status) {
                        com.meshcipher.domain.model.WifiDirectPeer.ConnectionStatus.CONNECTED -> 
                            MaterialTheme.colorScheme.primary
                        com.meshcipher.domain.model.WifiDirectPeer.ConnectionStatus.AVAILABLE -> 
                            MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        text = peer.status.name,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            
            if (peer.status == com.meshcipher.domain.model.WifiDirectPeer.ConnectionStatus.AVAILABLE) {
                Button(onClick = onConnect) {
                    Text("Connect")
                }
            }
        }
    }
}
```

### WifiDirectViewModel.kt

```kotlin
package com.meshcipher.presentation.wifidirect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.wifidirect.WifiDirectManager
import com.meshcipher.data.wifidirect.WifiDirectSocketManager
import com.meshcipher.domain.model.WifiDirectPeer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WifiDirectViewModel @Inject constructor(
    private val wifiDirectManager: WifiDirectManager,
    private val socketManager: WifiDirectSocketManager
) : ViewModel() {
    
    val discoveredPeers: StateFlow<List<WifiDirectPeer>> = 
        wifiDirectManager.discoveredPeers
    
    val connectionInfo = wifiDirectManager.connectionInfo
    val groupInfo = wifiDirectManager.groupInfo
    
    private val _isDiscovering = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering
    
    init {
        wifiDirectManager.initialize()
    }
    
    fun toggleDiscovery() {
        if (_isDiscovering.value) {
            wifiDirectManager.stopDiscovery()
            _isDiscovering.value = false
        } else {
            wifiDirectManager.startDiscovery()
            _isDiscovering.value = true
        }
    }
    
    fun connectToPeer(peer: WifiDirectPeer) {
        viewModelScope.launch {
            wifiDirectManager.connect(peer)
        }
    }
    
    fun createGroup() {
        viewModelScope.launch {
            wifiDirectManager.createGroup()
            
            // Start server socket for incoming connections
            socketManager.startServer(viewModelScope)
        }
    }
    
    fun disconnect() {
        wifiDirectManager.disconnect()
        socketManager.cleanup()
    }
    
    override fun onCleared() {
        super.onCleared()
        wifiDirectManager.cleanup()
        socketManager.cleanup()
    }
}
```

---

## Step 8: Hybrid Mode

### TransportSelector.kt

```kotlin
package com.meshcipher.data.transport

import com.meshcipher.domain.model.Contact
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransportSelector @Inject constructor(
    private val bluetoothMeshTransport: BluetoothMeshTransport,
    private val wifiDirectTransport: WifiDirectTransport,
    private val internetTransport: InternetTransport
) {
    
    /**
     * Select best transport for message based on:
     * - Contact reachability
     * - Message size
     * - Transport capabilities
     */
    suspend fun selectBestTransport(
        contact: Contact,
        messageSizeBytes: Long
    ): Transport? {
        // For large messages, prefer WiFi Direct
        if (messageSizeBytes > 1024 * 1024) { // >1MB
            if (wifiDirectTransport.isAvailable() && 
                wifiDirectTransport.isContactReachable(contact)) {
                Timber.d("Selected WiFi Direct for large message")
                return wifiDirectTransport
            }
        }
        
        // For medium messages, prefer Bluetooth or WiFi
        if (messageSizeBytes > 100 * 1024) { // >100KB
            if (wifiDirectTransport.isAvailable() && 
                wifiDirectTransport.isContactReachable(contact)) {
                return wifiDirectTransport
            }
            
            if (bluetoothMeshTransport.isAvailable() && 
                bluetoothMeshTransport.isContactReachable(contact)) {
                return bluetoothMeshTransport
            }
        }
        
        // Default to internet if available
        if (internetTransport.isAvailable()) {
            return internetTransport
        }
        
        // Fallback to any available local transport
        return when {
            bluetoothMeshTransport.isAvailable() -> bluetoothMeshTransport
            wifiDirectTransport.isAvailable() -> wifiDirectTransport
            else -> null
        }
    }
    
    /**
     * Use hybrid mode: Send via both WiFi Direct and Bluetooth
     * for redundancy and speed
     */
    suspend fun sendViaHybrid(
        message: com.meshcipher.domain.model.EncryptedMessage,
        recipient: Contact
    ): SendResult {
        val results = mutableListOf<SendResult>()
        
        // Try WiFi Direct (faster)
        if (wifiDirectTransport.isAvailable()) {
            results.add(wifiDirectTransport.send(message, recipient))
        }
        
        // Try Bluetooth (more reliable for small messages)
        if (bluetoothMeshTransport.isAvailable()) {
            results.add(bluetoothMeshTransport.send(message, recipient))
        }
        
        // Return success if any transport succeeded
        return results.firstOrNull { it is SendResult.Success }
            ?: SendResult.Failed("All hybrid transports failed")
    }
}
```

---

## Step 9: Update Settings

### SettingsScreen.kt (Updated)

Add WiFi Direct toggle:

```kotlin
// In Settings
Section(title = "Offline Transports") {
    SwitchPreference(
        title = "Bluetooth Mesh",
        subtitle = "Short-range (10m), low power",
        checked = bluetoothEnabled,
        onCheckedChange = { viewModel.setBluetoothEnabled(it) }
    )
    
    SwitchPreference(
        title = "WiFi Direct",
        subtitle = "Medium-range (100m), high bandwidth",
        checked = wifiDirectEnabled,
        onCheckedChange = { viewModel.setWifiDirectEnabled(it) }
    )
    
    // Navigate to WiFi Direct screen
    Preference(
        title = "WiFi Direct Settings",
        subtitle = "Manage connections and groups",
        onClick = { navController.navigate("wifi_direct") }
    )
}
```

---

## Phase 5 Checklist

- [ ] WiFi Direct permissions added
- [ ] WifiDirectPeer model created
- [ ] WifiDirectManager implemented
- [ ] Peer discovery working
- [ ] Group creation working
- [ ] WifiDirectSocketManager created
- [ ] Server socket working
- [ ] Client socket working
- [ ] File transfer working
- [ ] WifiDirectTransport created
- [ ] TransportManager updated
- [ ] WiFi Direct UI screen
- [ ] Settings integration
- [ ] Hybrid mode (BT + WiFi)
- [ ] TransportSelector logic
- [ ] Works offline
- [ ] Tests written
- [ ] 100m range verified

---

## Testing Guide

### Test 1: WiFi Direct Discovery
```
1. Device A: Enable WiFi Direct
2. Device B: Enable WiFi Direct
3. Both: Start discovery
4. Should find each other
5. Shows in peer list
```

### Test 2: Group Formation
```
1. Device A: Create group (becomes GO)
2. Device B: Connect to A
3. B receives IP via DHCP
4. Socket connection established
5. Can send messages
```

### Test 3: File Transfer
```
1. Connected via WiFi Direct
2. Send large file (50MB video)
3. Progress updates in UI
4. Transfer completes faster than Bluetooth
5. File received correctly
```

### Test 4: Hybrid Mode
```
1. Enable both Bluetooth and WiFi Direct
2. Send message
3. Goes via WiFi Direct (preferred)
4. If WiFi fails, falls back to Bluetooth
5. Redundancy working
```

---

## Next Phase

**Phase 6: P2P Mode (TOR Hidden Services)** - See `Phase_6_P2P_TOR.md`

Final phase:
- No relay server
- Each user as TOR hidden service
- Direct peer-to-peer
- Maximum privacy
- Complete decentralization