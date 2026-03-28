package com.meshcipher.data.transport

import com.google.gson.Gson
import com.meshcipher.BuildConfig
import com.meshcipher.data.auth.DynamicBaseUrlInterceptor
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.data.remote.api.RelayApiService
import com.meshcipher.data.tor.TorManager
import com.meshcipher.domain.model.ConnectionMode
import com.meshcipher.domain.repository.PrivacyProfileRepository
import com.meshcipher.util.MessagePadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TransportManager"

@Singleton
class TransportManager @Inject constructor(
    private val directTransport: InternetTransport,
    private val appPreferences: AppPreferences,
    private val torManager: TorManager,
    private val gson: Gson,
    private val retrofit: Retrofit,
    private val bluetoothMeshTransport: BluetoothMeshTransport,
    private val wifiDirectTransport: WifiDirectTransport,
    private val p2pTransport: P2PTransport,
    private val dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor,
    private val smartModeManager: SmartModeManager,
    private val relayHealthMonitor: RelayHealthMonitor,
    private val privacyProfileRepository: PrivacyProfileRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val currentMode = AtomicReference(ConnectionMode.DIRECT)

    @Volatile private var smartModeEnabled = true
    @Volatile private var preferTor = false
    // MD-02: true when padding should be applied to outbound messages.
    @Volatile private var paddingEnabled = false

    @Volatile
    private var torTransport: InternetTransport? = null

    init {
        scope.launch {
            appPreferences.connectionMode
                .map { name ->
                    try {
                        ConnectionMode.valueOf(name)
                    } catch (e: IllegalArgumentException) {
                        ConnectionMode.DIRECT
                    }
                }
                .collect { mode ->
                    currentMode.set(mode)
                    Timber.d("Connection mode changed to: %s", mode)
                }
        }
        scope.launch {
            appPreferences.smartModeEnabled.collect { enabled ->
                smartModeEnabled = enabled
                Timber.d("Smart Mode: %s", if (enabled) "enabled" else "disabled")
            }
        }
        scope.launch {
            appPreferences.preferTor.collect { pref ->
                preferTor = pref
                Timber.d("Prefer TOR: %s", pref)
            }
        }
        relayHealthMonitor.startMonitoring()
        // MD-02: observe privacy profile to enable/disable message padding.
        scope.launch {
            privacyProfileRepository.privacyProfile.collect { profile ->
                paddingEnabled = profile.isEnhanced()
                Timber.d("Message padding: %s (profile=%s)", if (paddingEnabled) "enabled" else "disabled", profile)
            }
        }
    }

    fun getActiveTransport(): InternetTransport {
        return when (currentMode.get()) {
            ConnectionMode.TOR_RELAY -> getOrCreateTorTransport()
            else -> directTransport
        }
    }

    fun getConnectionMode(): ConnectionMode = currentMode.get()

    fun getSmartModeManager(): SmartModeManager = smartModeManager

    fun getBluetoothMeshTransport(): BluetoothMeshTransport = bluetoothMeshTransport

    fun getWifiDirectTransport(): WifiDirectTransport = wifiDirectTransport

    fun getP2PTransport(): P2PTransport = p2pTransport

    fun isMeshAvailable(): Boolean = bluetoothMeshTransport.isAvailable()

    fun isWifiDirectAvailable(): Boolean = wifiDirectTransport.isAvailable()

    fun isP2PAvailable(): Boolean = p2pTransport.isAvailable()

    suspend fun sendWithFallback(
        senderId: String,
        recipientId: String,
        encryptedContent: ByteArray,
        contentType: Int = 0
    ): Result<String> {
        // MD-02: pad payload to a fixed-size block boundary when HIGH_PRIVACY/MAXIMUM is active.
        // Padding is applied here, outside Signal Protocol encryption, so the wire ciphertext
        // length is normalised and cannot reveal content type via traffic analysis.
        val outboundContent = if (paddingEnabled) {
            try {
                MessagePadding.pad(encryptedContent)
            } catch (e: IllegalArgumentException) {
                Timber.e(e, "$TAG: payload too large to pad, rejecting send")
                return Result.failure(e)
            }
        } else {
            encryptedContent
        }

        // Compute effective ConnectionMode: Smart Mode overrides manual selection.
        //   - Smart Mode OFF → use stored ConnectionMode as-is.
        //   - Smart Mode ON + preferTor → effective DIRECT mode, but use TOR transport
        //     for the internet leg (handled by getEffectiveInternetTransport).
        //   - Smart Mode ON + !preferTor → effective DIRECT mode (speed-first fallback).
        val storedMode = currentMode.get()
        val effectiveMode = if (smartModeEnabled) {
            // Smart Mode only overrides DIRECT/TOR_RELAY; P2P_TOR and P2P_ONLY are always explicit.
            when (storedMode) {
                ConnectionMode.P2P_TOR, ConnectionMode.P2P_ONLY -> storedMode
                else -> ConnectionMode.DIRECT
            }
        } else {
            storedMode
        }

        Timber.d(
            "$TAG: send to %s | stored=%s effective=%s smart=%b preferTor=%b",
            recipientId, storedMode, effectiveMode, smartModeEnabled, preferTor
        )

        // ── P2P_TOR: P2P Tor → WiFi Direct → Bluetooth ──────────────────────
        if (effectiveMode == ConnectionMode.P2P_TOR) {
            if (p2pTransport.isAvailable()) {
                val p2pResult = p2pTransport.sendMessage(recipientId, outboundContent, contentType)
                if (p2pResult.isSuccess) {
                    Timber.d("$TAG: sent via P2P Tor")
                    smartModeManager.reportTransportUsed(SmartModeManager.ActiveTransport.P2P_TOR)
                    return p2pResult
                }
                val p2pError = p2pResult.exceptionOrNull()?.message ?: "P2P send failed"
                Timber.d("$TAG: P2P Tor failed: %s, trying WiFi Direct", p2pError)
                if (!wifiDirectTransport.isAvailable() && !bluetoothMeshTransport.isAvailable()) {
                    return p2pResult
                }
            } else {
                Timber.d("$TAG: P2P Tor not available")
            }
            if (wifiDirectTransport.isAvailable()) {
                val wifiResult = wifiDirectTransport.sendMessage(recipientId, outboundContent, contentType)
                if (wifiResult.isSuccess) {
                    smartModeManager.reportTransportUsed(SmartModeManager.ActiveTransport.WIFI_DIRECT)
                    return wifiResult
                }
                Timber.d("$TAG: WiFi Direct failed, trying Bluetooth mesh")
            }
            if (bluetoothMeshTransport.isAvailable()) {
                val btResult = bluetoothMeshTransport.sendMessage(recipientId, outboundContent)
                if (btResult.isSuccess) {
                    smartModeManager.reportTransportUsed(SmartModeManager.ActiveTransport.BLUETOOTH)
                }
                return btResult
            }
            return Result.failure(Exception(
                if (p2pTransport.isAvailable()) "P2P Tor send failed"
                else "P2P Tor is not running. Start it from Settings > P2P Tor."
            ))
        }

        // ── P2P_ONLY: WiFi Direct → Bluetooth ───────────────────────────────
        if (effectiveMode == ConnectionMode.P2P_ONLY) {
            if (wifiDirectTransport.isAvailable()) {
                val wifiResult = wifiDirectTransport.sendMessage(recipientId, outboundContent, contentType)
                if (wifiResult.isSuccess) {
                    smartModeManager.reportTransportUsed(SmartModeManager.ActiveTransport.WIFI_DIRECT)
                    return wifiResult
                }
                Timber.d("$TAG: WiFi Direct failed, trying Bluetooth mesh")
            }
            val btResult = bluetoothMeshTransport.sendMessage(recipientId, outboundContent)
            if (btResult.isSuccess) {
                smartModeManager.reportTransportUsed(SmartModeManager.ActiveTransport.BLUETOOTH)
            }
            return btResult
        }

        // ── DIRECT / TOR_RELAY / Smart Mode: P2P Tor → WiFi Direct → Internet → Bluetooth ──
        if (p2pTransport.isAvailable() && p2pTransport.isRecipientReachable(recipientId)) {
            val p2pResult = p2pTransport.sendMessage(recipientId, outboundContent, contentType)
            if (p2pResult.isSuccess) {
                Timber.d("$TAG: sent via P2P Tor (opportunistic)")
                smartModeManager.reportTransportUsed(SmartModeManager.ActiveTransport.P2P_TOR)
                return p2pResult
            }
            Timber.d("$TAG: opportunistic P2P Tor failed, continuing")
        }

        if (wifiDirectTransport.isAvailable()) {
            val wifiResult = wifiDirectTransport.sendMessage(recipientId, outboundContent, contentType)
            if (wifiResult.isSuccess) {
                Timber.d("$TAG: sent via WiFi Direct")
                smartModeManager.reportTransportUsed(SmartModeManager.ActiveTransport.WIFI_DIRECT)
                return wifiResult
            }
            Timber.d("$TAG: WiFi Direct failed, trying internet")
        }

        // RM-12 / R-13: Skip relay entirely if health monitor reports OFFLINE.
        val relayOffline = relayHealthMonitor.healthState.value == RelayHealthMonitor.RelayHealthState.OFFLINE
        if (relayOffline) {
            Timber.d("$TAG: relay OFFLINE — skipping internet transport, prefer local fallback")
            if (bluetoothMeshTransport.isAvailable()) {
                val btResult = bluetoothMeshTransport.sendMessage(recipientId, encryptedContent)
                if (btResult.isSuccess) smartModeManager.reportTransportUsed(SmartModeManager.ActiveTransport.BLUETOOTH)
                return btResult
            }
            return Result.failure(Exception("Relay unavailable and no local transport reachable"))
        }

        // Internet transport: in Smart Mode use TOR if preferTor is on; otherwise use
        // whichever transport the stored mode dictates.
        val internetTransport = getEffectiveInternetTransport()
        val internetResult = try {
            internetTransport.sendMessage(senderId, recipientId, outboundContent, contentType)
        } catch (e: Exception) {
            Timber.w(e, "$TAG: internet transport failed")
            Result.failure(e)
        }

        if (internetResult.isSuccess) {
            val usedTor = smartModeEnabled && preferTor || storedMode == ConnectionMode.TOR_RELAY
            smartModeManager.reportTransportUsed(
                if (usedTor) SmartModeManager.ActiveTransport.TOR_RELAY
                else SmartModeManager.ActiveTransport.INTERNET
            )
            return internetResult
        }

        if (bluetoothMeshTransport.isAvailable()) {
            Timber.d("$TAG: falling back to Bluetooth mesh")
            val btResult = bluetoothMeshTransport.sendMessage(recipientId, outboundContent)
            if (btResult.isSuccess) {
                smartModeManager.reportTransportUsed(SmartModeManager.ActiveTransport.BLUETOOTH)
            }
            return btResult
        }

        return internetResult
    }

    /**
     * Returns the internet-layer transport to use.
     * In Smart Mode with [preferTor] ON, or when the stored mode is [ConnectionMode.TOR_RELAY],
     * this returns the TOR-proxied transport; otherwise the direct transport.
     */
    private fun getEffectiveInternetTransport(): InternetTransport {
        val useTor = (smartModeEnabled && preferTor) || currentMode.get() == ConnectionMode.TOR_RELAY
        return if (useTor) getOrCreateTorTransport() else directTransport
    }

    private fun getOrCreateTorTransport(): InternetTransport {
        torTransport?.let { return it }

        synchronized(this) {
            torTransport?.let { return it }

            val proxy = torManager.createTorProxy()

            val torClient = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .addInterceptor(dynamicBaseUrlInterceptor)
                .apply {
                    if (BuildConfig.DEBUG) {
                        val loggingInterceptor = HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BODY
                        }
                        addInterceptor(loggingInterceptor)
                    }
                }
                .build()

            val torRetrofit = retrofit.newBuilder()
                .client(torClient)
                .build()

            val torApiService = torRetrofit.create(RelayApiService::class.java)
            val transport = InternetTransport(torApiService)
            torTransport = transport

            Timber.d("TOR transport created with SOCKS proxy")
            return transport
        }
    }

    fun clearTorTransport() {
        synchronized(this) {
            torTransport = null
            Timber.d("TOR transport cleared")
        }
    }
}
