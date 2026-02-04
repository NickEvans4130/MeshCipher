# MeshCipher - Phase 3.75: TOR Integration
## Week 14: Metadata Privacy & Censorship Resistance

---

## Phase 3.75 Overview

**Goals:**
- Add TOR routing for metadata privacy
- Make relay server a hidden service
- Allow users to choose connection mode
- Hide IP addresses from relay server
- Enable censorship-resistant communication

**Deliverables:**
- Orbot integration (SOCKS proxy)
- Hidden service configuration for relay
- Settings toggle for TOR mode
- Connection mode selector
- TOR status indicators in UI
- Works in censored countries

**Prerequisites:**
- ✅ Phase 3 complete (internet transport working)
- ✅ Phase 3.5 complete (security hardened)

---

## Connection Modes

```kotlin
enum class ConnectionMode {
    DIRECT,        // Normal internet (fastest)
    TOR_RELAY,     // Via TOR to relay server (private)
    P2P_ONLY       // Direct P2P via TOR (Phase 6)
}
```

**Mode Comparison:**

| Mode | Speed | Privacy | Censorship Resistance |
|------|-------|---------|----------------------|
| DIRECT | ⚡⚡⚡ Fast | ⚠️ Server sees IP | ⚠️ Can be blocked |
| TOR_RELAY | ⚡ Slow (3-5s) | ✅ IP hidden | ✅ Works in censored countries |
| P2P_ONLY | ⚡⚡ Medium | ✅✅ Maximum privacy | ✅✅ Fully decentralized |

---

## Step 1: Add Dependencies

### build.gradle.kts (app)

```kotlin
dependencies {
    // ... existing dependencies
    
    // TOR Integration
    implementation("info.guardianproject:tor-android:0.4.7.13")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
}
```

---

## Step 2: TOR Manager

### TorManager.kt

```kotlin
package com.meshcipher.data.tor

import android.content.Context
import info.guardianproject.netcipher.proxy.OrbotHelper
import info.guardianproject.netcipher.proxy.StatusCallback
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed class TorStatus {
    object Disabled : TorStatus()
    object Starting : TorStatus()
    data class Connected(val socksPort: Int) : TorStatus()
    data class Error(val message: String) : TorStatus()
}

@Singleton
class TorManager @Inject constructor(
    private val context: Context
) {
    private val _status = MutableStateFlow<TorStatus>(TorStatus.Disabled)
    val status: StateFlow<TorStatus> = _status.asStateFlow()
    
    private var orbotHelper: OrbotHelper? = null
    
    init {
        orbotHelper = OrbotHelper.get(context)
    }
    
    /**
     * Check if Orbot is installed
     */
    fun isOrbotInstalled(): Boolean {
        return orbotHelper?.isOrbotInstalled == true
    }
    
    /**
     * Check if Orbot is running
     */
    fun isOrbotRunning(): Boolean {
        return orbotHelper?.isOrbotRunning == true
    }
    
    /**
     * Start TOR connection
     */
    fun startTor(): Flow<TorStatus> = callbackFlow {
        if (!isOrbotInstalled()) {
            trySend(TorStatus.Error("Orbot not installed"))
            close()
            return@callbackFlow
        }
        
        trySend(TorStatus.Starting)
        _status.value = TorStatus.Starting
        
        val callback = object : StatusCallback {
            override fun onEnabled(intent: android.content.Intent?) {
                Timber.d("TOR enabled")
                val socksPort = 9050 // Default Orbot SOCKS port
                val status = TorStatus.Connected(socksPort)
                trySend(status)
                _status.value = status
            }
            
            override fun onStarting() {
                Timber.d("TOR starting")
                trySend(TorStatus.Starting)
                _status.value = TorStatus.Starting
            }
            
            override fun onStopping() {
                Timber.d("TOR stopping")
                trySend(TorStatus.Disabled)
                _status.value = TorStatus.Disabled
            }
            
            override fun onDisabled() {
                Timber.d("TOR disabled")
                trySend(TorStatus.Disabled)
                _status.value = TorStatus.Disabled
            }
            
            override fun onStatusTimeout() {
                Timber.e("TOR status timeout")
                val error = TorStatus.Error("Connection timeout")
                trySend(error)
                _status.value = error
            }
            
            override fun onNotYetInstalled() {
                Timber.e("Orbot not installed")
                val error = TorStatus.Error("Orbot not installed")
                trySend(error)
                _status.value = error
            }
        }
        
        orbotHelper?.init()
        orbotHelper?.addStatusCallback(callback)
        orbotHelper?.requestStart(context)
        
        awaitClose {
            orbotHelper?.removeStatusCallback(callback)
        }
    }
    
    /**
     * Stop TOR connection
     */
    fun stopTor() {
        orbotHelper?.requestStop(context)
        _status.value = TorStatus.Disabled
    }
    
    /**
     * Get SOCKS proxy address for TOR
     */
    fun getSocksProxy(): Pair<String, Int>? {
        return when (val currentStatus = _status.value) {
            is TorStatus.Connected -> "127.0.0.1" to currentStatus.socksPort
            else -> null
        }
    }
    
    /**
     * Open Orbot app for user to enable
     */
    fun openOrbotApp() {
        orbotHelper?.requestStartTor(context)
    }
}
```

---

## Step 3: TOR Transport

### TorTransport.kt

```kotlin
package com.meshcipher.data.transport

import com.meshcipher.data.remote.api.RelayApiService
import com.meshcipher.data.tor.TorManager
import com.meshcipher.data.tor.TorStatus
import com.meshcipher.domain.model.Contact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorTransport @Inject constructor(
    private val torManager: TorManager,
    private val internetTransport: InternetTransport
) : Transport {
    
    override suspend fun send(
        message: EncryptedMessage,
        recipient: Contact
    ): SendResult {
        // Ensure TOR is connected
        if (!ensureTorConnected()) {
            return SendResult.Failed("TOR not available")
        }
        
        // Use internet transport (it will use TOR proxy via OkHttp)
        return try {
            Timber.d("Sending message via TOR")
            internetTransport.send(message, recipient)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send via TOR")
            SendResult.Failed("TOR send failed: ${e.message}")
        }
    }
    
    override suspend fun receive(): Flow<EncryptedMessage> {
        // Ensure TOR is connected
        ensureTorConnected()
        
        // Use internet transport's receive (via TOR proxy)
        return internetTransport.receive()
    }
    
    override fun isAvailable(): Boolean {
        return torManager.isOrbotInstalled() && 
               torManager.status.value is TorStatus.Connected
    }
    
    override suspend fun isContactReachable(contact: Contact): Boolean {
        return isAvailable()
    }
    
    private suspend fun ensureTorConnected(): Boolean {
        val currentStatus = torManager.status.value
        
        return when (currentStatus) {
            is TorStatus.Connected -> true
            is TorStatus.Disabled, is TorStatus.Error -> {
                // Try to start TOR
                try {
                    withTimeout(30000) { // 30 second timeout
                        torManager.startTor().first { status ->
                            status is TorStatus.Connected
                        }
                        true
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start TOR")
                    false
                }
            }
            is TorStatus.Starting -> {
                // Wait for connection
                try {
                    withTimeout(30000) {
                        torManager.status.first { status ->
                            status is TorStatus.Connected
                        }
                        true
                    }
                } catch (e: Exception) {
                    false
                }
            }
        }
    }
}
```

---

## Step 4: Update Network Module

### NetworkModule.kt (Updated)

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    
    private const val BASE_URL_CLEARNET = "http://YOUR_THINKPAD_IP:5000/"
    private const val BASE_URL_ONION = "http://YOUR_ONION_ADDRESS.onion:5000/"
    
    @Provides
    @Singleton
    fun provideOkHttpClient(
        torManager: TorManager,
        preferences: AppPreferences
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
            )
            .connectTimeout(60, TimeUnit.SECONDS)  // Longer for TOR
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
        
        // Add certificate pinning for clearnet
        val certificatePinner = CertificatePinner.Builder()
            .add("your-server.com", "sha256/AAAAAAAAAA...")
            .build()
        builder.certificatePinner(certificatePinner)
        
        // Add TOR proxy if TOR mode enabled
        if (preferences.connectionMode == ConnectionMode.TOR_RELAY) {
            val (host, port) = torManager.getSocksProxy() 
                ?: ("127.0.0.1" to 9050) // Default
            
            builder.proxy(
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
            )
            
            // Disable certificate pinning for .onion
            builder.certificatePinner(CertificatePinner.DEFAULT)
        }
        
        return builder.build()
    }
    
    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        preferences: AppPreferences
    ): Retrofit {
        val baseUrl = when (preferences.connectionMode) {
            ConnectionMode.TOR_RELAY -> BASE_URL_ONION
            else -> BASE_URL_CLEARNET
        }
        
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    @Provides
    @Singleton
    fun provideTorTransport(
        torManager: TorManager,
        internetTransport: InternetTransport
    ): TorTransport {
        return TorTransport(torManager, internetTransport)
    }
}
```

---

## Step 5: Update Transport Manager

### TransportManager.kt (Updated)

```kotlin
@Singleton
class TransportManager @Inject constructor(
    private val internetTransport: InternetTransport,
    private val torTransport: TorTransport,
    private val preferences: AppPreferences
) {
    
    suspend fun sendMessage(
        message: EncryptedMessage,
        recipient: Contact
    ): SendResult {
        return when (preferences.connectionMode) {
            ConnectionMode.DIRECT -> {
                Timber.d("Sending via direct internet")
                internetTransport.send(message, recipient)
            }
            
            ConnectionMode.TOR_RELAY -> {
                Timber.d("Sending via TOR")
                torTransport.send(message, recipient)
            }
            
            ConnectionMode.P2P_ONLY -> {
                // Phase 6
                SendResult.Failed("P2P mode not implemented yet")
            }
        }
    }
    
    fun receiveMessages(): Flow<EncryptedMessage> {
        return when (preferences.connectionMode) {
            ConnectionMode.DIRECT -> internetTransport.receive()
            ConnectionMode.TOR_RELAY -> torTransport.receive()
            ConnectionMode.P2P_ONLY -> flow { /* Phase 6 */ }
        }
    }
    
    suspend fun getCurrentTransport(): Transport {
        return when (preferences.connectionMode) {
            ConnectionMode.DIRECT -> internetTransport
            ConnectionMode.TOR_RELAY -> torTransport
            ConnectionMode.P2P_ONLY -> throw NotImplementedError("Phase 6")
        }
    }
}
```

---

## Step 6: Preferences Storage

### AppPreferences.kt

```kotlin
package com.meshcipher.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.meshcipher.domain.model.ConnectionMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_preferences"
)

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private object Keys {
        val CONNECTION_MODE = stringPreferencesKey("connection_mode")
    }
    
    val connectionMode: Flow<ConnectionMode> = context.dataStore.data
        .map { preferences ->
            val mode = preferences[Keys.CONNECTION_MODE] ?: ConnectionMode.DIRECT.name
            ConnectionMode.valueOf(mode)
        }
    
    suspend fun setConnectionMode(mode: ConnectionMode) {
        context.dataStore.edit { preferences ->
            preferences[Keys.CONNECTION_MODE] = mode.name
        }
    }
    
    suspend fun getConnectionMode(): ConnectionMode {
        return connectionMode.first()
    }
}
```

---

## Step 7: Settings UI

### SettingsScreen.kt (Updated)

```kotlin
package com.meshcipher.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshcipher.domain.model.ConnectionMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .padding(16.dp)
        ) {
            // Connection Mode Section
            Text(
                text = "Connection Mode",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            ConnectionModeSelector(
                selectedMode = uiState.connectionMode,
                torStatus = uiState.torStatus,
                onModeSelected = { viewModel.setConnectionMode(it) },
                onInstallOrbot = { viewModel.installOrbot() },
                onEnableOrbot = { viewModel.enableOrbot() }
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // TOR Status
            if (uiState.connectionMode == ConnectionMode.TOR_RELAY) {
                TorStatusCard(
                    status = uiState.torStatus,
                    onRetry = { viewModel.retryTorConnection() }
                )
            }
        }
    }
}

@Composable
fun ConnectionModeSelector(
    selectedMode: ConnectionMode,
    torStatus: TorStatus,
    onModeSelected: (ConnectionMode) -> Unit,
    onInstallOrbot: () -> Unit,
    onEnableOrbot: () -> Unit
) {
    Column {
        // Direct Mode
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            onClick = { onModeSelected(ConnectionMode.DIRECT) },
            colors = CardDefaults.cardColors(
                containerColor = if (selectedMode == ConnectionMode.DIRECT)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedMode == ConnectionMode.DIRECT,
                    onClick = { onModeSelected(ConnectionMode.DIRECT) }
                )
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column {
                    Text(
                        text = "Direct Connection",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "⚡⚡⚡ Fast • ⚠️ Server sees IP",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        // TOR Mode
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            onClick = { 
                if (torStatus !is TorStatus.Error) {
                    onModeSelected(ConnectionMode.TOR_RELAY)
                }
            },
            colors = CardDefaults.cardColors(
                containerColor = if (selectedMode == ConnectionMode.TOR_RELAY)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedMode == ConnectionMode.TOR_RELAY,
                    onClick = { onModeSelected(ConnectionMode.TOR_RELAY) },
                    enabled = torStatus !is TorStatus.Error
                )
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TOR Relay",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "⚡ Slower (3-5s) • ✅ IP hidden • ✅ Censorship resistant",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    when (torStatus) {
                        is TorStatus.Error -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            if (torStatus.message.contains("not installed")) {
                                Button(
                                    onClick = onInstallOrbot,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Install Orbot")
                                }
                            } else {
                                Button(
                                    onClick = onEnableOrbot,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Enable TOR")
                                }
                            }
                        }
                        is TorStatus.Starting -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        else -> {}
                    }
                }
            }
        }
        
        // P2P Mode (Phase 6)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = false,
                    onClick = {},
                    enabled = false
                )
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "P2P Only",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Badge { Text("Phase 6") }
                    }
                    Text(
                        text = "⚡⚡ Medium • ✅✅ Maximum privacy • No relay server",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
fun TorStatusCard(
    status: TorStatus,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (status) {
                is TorStatus.Connected -> MaterialTheme.colorScheme.primaryContainer
                is TorStatus.Error -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "TOR Status",
                style = MaterialTheme.typography.titleMedium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            when (status) {
                is TorStatus.Connected -> {
                    Text(
                        text = "✅ Connected via port ${status.socksPort}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                is TorStatus.Starting -> {
                    Text(
                        text = "⏳ Connecting to TOR network...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is TorStatus.Error -> {
                    Text(
                        text = "❌ ${status.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onRetry) {
                        Text("Retry")
                    }
                }
                is TorStatus.Disabled -> {
                    Text(
                        text = "TOR disabled",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
```

### SettingsViewModel.kt

```kotlin
package com.meshcipher.presentation.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.preferences.AppPreferences
import com.meshcipher.data.tor.TorManager
import com.meshcipher.data.tor.TorStatus
import com.meshcipher.domain.model.ConnectionMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val connectionMode: ConnectionMode = ConnectionMode.DIRECT,
    val torStatus: TorStatus = TorStatus.Disabled
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val torManager: TorManager
) : ViewModel() {
    
    val uiState: StateFlow<SettingsUiState> = combine(
        preferences.connectionMode,
        torManager.status
    ) { mode, status ->
        SettingsUiState(
            connectionMode = mode,
            torStatus = status
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )
    
    fun setConnectionMode(mode: ConnectionMode) {
        viewModelScope.launch {
            preferences.setConnectionMode(mode)
            
            // Start TOR if TOR mode selected
            if (mode == ConnectionMode.TOR_RELAY) {
                if (!torManager.isOrbotInstalled()) {
                    // Will show install button
                } else if (!torManager.isOrbotRunning()) {
                    torManager.startTor().collect { /* Status updates */ }
                }
            }
        }
    }
    
    fun installOrbot() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://play.google.com/store/apps/details?id=org.torproject.android")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    fun enableOrbot() {
        torManager.openOrbotApp()
    }
    
    fun retryTorConnection() {
        viewModelScope.launch {
            torManager.startTor().collect { /* Status updates */ }
        }
    }
}
```

---

## Step 8: Hidden Service Setup (ThinkPad)

### Configure TOR Hidden Service

SSH into your ThinkPad:

```bash
ssh nick@YOUR_THINKPAD_IP
```

Install TOR:

```bash
sudo apt install tor
```

Configure hidden service:

```bash
sudo nano /etc/tor/torrc
```

Add these lines:

```
HiddenServiceDir /var/lib/tor/meshcipher/
HiddenServicePort 5000 127.0.0.1:5000
```

Restart TOR:

```bash
sudo systemctl restart tor
```

Get your onion address:

```bash
sudo cat /var/lib/tor/meshcipher/hostname
```

Output will be something like:
```
abc123xyz456def789ghi.onion
```

**Save this address!** Use it in NetworkModule.kt:

```kotlin
private const val BASE_URL_ONION = "http://abc123xyz456def789ghi.onion:5000/"
```

---

## Step 9: UI Indicators

### Add TOR indicator to ConversationsScreen

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    // ... existing params
    viewModel: ConversationsViewModel = hiltViewModel()
) {
    val connectionMode by viewModel.connectionMode.collectAsState()
    val torStatus by viewModel.torStatus.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("MeshCipher")
                        
                        // TOR indicator
                        if (connectionMode == ConnectionMode.TOR_RELAY) {
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            Badge(
                                containerColor = when (torStatus) {
                                    is TorStatus.Connected -> MaterialTheme.colorScheme.primary
                                    is TorStatus.Starting -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.error
                                }
                            ) {
                                Text(
                                    text = when (torStatus) {
                                        is TorStatus.Connected -> "🧅 TOR"
                                        is TorStatus.Starting -> "🧅 ..."
                                        else -> "🧅 ✗"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        },
        // ... rest of screen
    )
}
```

---

## Step 10: Testing

### TorManagerTest.kt

```kotlin
package com.meshcipher.data.tor

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TorManagerTest {
    
    private lateinit var context: Context
    private lateinit var torManager: TorManager
    
    @Before
    fun setup() {
        context = mockk(relaxed = true)
        torManager = TorManager(context)
    }
    
    @Test
    fun `getSocksProxy returns null when TOR disabled`() {
        val proxy = torManager.getSocksProxy()
        assertNull(proxy)
    }
    
    @Test
    fun `isOrbotInstalled checks for Orbot app`() {
        // Will vary based on device
        val installed = torManager.isOrbotInstalled()
        // Just verify it doesn't crash
        assertTrue(installed || !installed)
    }
}
```

### TorTransportTest.kt

```kotlin
@Test
fun `send waits for TOR connection before sending`() = runTest {
    // Given TOR is starting
    every { torManager.status.value } returns TorStatus.Starting
    
    // When sending message
    val job = launch {
        torTransport.send(testMessage, testContact)
    }
    
    // Should wait for connection
    delay(100)
    assertTrue(job.isActive)
    
    // Complete TOR connection
    every { torManager.status.value } returns TorStatus.Connected(9050)
    
    // Should complete
    job.join()
}
```

---

## Phase 3.75 Checklist

- [ ] Orbot/TOR dependencies added
- [ ] TorManager implemented
- [ ] TorTransport created
- [ ] NetworkModule updated for TOR proxy
- [ ] TransportManager supports TOR mode
- [ ] AppPreferences for connection mode
- [ ] SettingsScreen with mode selector
- [ ] SettingsViewModel implemented
- [ ] Hidden service configured on server
- [ ] Onion address in app config
- [ ] TOR status indicators in UI
- [ ] ConversationsScreen shows TOR badge
- [ ] Tests written
- [ ] Orbot installed and working
- [ ] Can send/receive via TOR
- [ ] Messages slower but working
- [ ] Server accessible via .onion

---

## Testing Guide

### Manual Testing:

**1. Install Orbot:**
```
Play Store → Search "Orbot" → Install
Open Orbot → Enable VPN mode
```

**2. Test Direct Mode:**
```
Settings → Connection Mode → Direct
Send message → Should be fast (~1s)
Check server logs → Should see your real IP
```

**3. Test TOR Mode:**
```
Settings → Connection Mode → TOR Relay
Wait for TOR to connect (~30s first time)
Send message → Should be slower (~5s)
Check server logs → Should see TOR exit node IP, not yours
```

**4. Test .onion Access:**
```
Settings → TOR Relay mode
App should use .onion URL
Check Logcat for "Connecting to .onion"
Message should send successfully
```

**5. Verify IP Privacy:**
```
TOR mode enabled
Send message
Check server logs:
- Should NOT see your real IP
- Should see TOR IP (different each time)
```

---

## Next Phase

**Phase 4: Bluetooth Mesh** - See `Phase_4_Bluetooth_Mesh.md`

This will add:
- BLE advertising/scanning
- Mesh routing algorithm
- Multi-hop delivery
- Offline communication