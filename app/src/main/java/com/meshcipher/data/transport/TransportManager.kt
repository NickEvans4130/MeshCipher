package com.meshcipher.data.transport

import com.google.gson.Gson
import com.meshcipher.BuildConfig
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.data.remote.api.RelayApiService
import com.meshcipher.data.tor.TorManager
import com.meshcipher.domain.model.ConnectionMode
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

@Singleton
class TransportManager @Inject constructor(
    private val directTransport: InternetTransport,
    private val appPreferences: AppPreferences,
    private val torManager: TorManager,
    private val gson: Gson,
    private val retrofit: Retrofit,
    private val bluetoothMeshTransport: BluetoothMeshTransport,
    private val wifiDirectTransport: WifiDirectTransport,
    private val p2pTransport: P2PTransport
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val currentMode = AtomicReference(ConnectionMode.DIRECT)

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
    }

    fun getActiveTransport(): InternetTransport {
        return when (currentMode.get()) {
            ConnectionMode.TOR_RELAY -> getOrCreateTorTransport()
            else -> directTransport
        }
    }

    fun getConnectionMode(): ConnectionMode = currentMode.get()

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
        val mode = currentMode.get()

        // P2P_TOR: Try P2P Tor first, then WiFi Direct, then Bluetooth mesh
        if (mode == ConnectionMode.P2P_TOR) {
            if (p2pTransport.isAvailable()) {
                val p2pResult = p2pTransport.sendMessage(recipientId, encryptedContent, contentType)
                if (p2pResult.isSuccess) {
                    Timber.d("Sent via P2P Tor to %s", recipientId)
                    return p2pResult
                }
                val p2pError = p2pResult.exceptionOrNull()?.message ?: "P2P send failed"
                Timber.d("P2P Tor failed: %s, trying WiFi Direct", p2pError)
                // If no other transports available, return the actual P2P error
                if (!wifiDirectTransport.isAvailable() && !bluetoothMeshTransport.isAvailable()) {
                    return p2pResult
                }
            } else {
                Timber.d("P2P Tor not available (state: not running)")
            }
            if (wifiDirectTransport.isAvailable()) {
                val wifiResult = wifiDirectTransport.sendMessage(recipientId, encryptedContent, contentType)
                if (wifiResult.isSuccess) return wifiResult
                Timber.d("WiFi Direct failed, trying Bluetooth mesh")
            }
            if (bluetoothMeshTransport.isAvailable()) {
                return bluetoothMeshTransport.sendMessage(recipientId, encryptedContent)
            }
            return Result.failure(Exception(
                if (p2pTransport.isAvailable()) "P2P Tor send failed"
                else "P2P Tor is not running. Start it from Settings > P2P Tor."
            ))
        }

        // P2P_ONLY: Try WiFi Direct first (better range/bandwidth), then Bluetooth mesh
        if (mode == ConnectionMode.P2P_ONLY) {
            if (wifiDirectTransport.isAvailable()) {
                val wifiResult = wifiDirectTransport.sendMessage(recipientId, encryptedContent, contentType)
                if (wifiResult.isSuccess) return wifiResult
                Timber.d("WiFi Direct failed, trying Bluetooth mesh")
            }
            return bluetoothMeshTransport.sendMessage(recipientId, encryptedContent)
        }

        // Try P2P Tor first if available and recipient is reachable
        if (p2pTransport.isAvailable() && p2pTransport.isRecipientReachable(recipientId)) {
            val p2pResult = p2pTransport.sendMessage(recipientId, encryptedContent, contentType)
            if (p2pResult.isSuccess) {
                Timber.d("Sent via P2P Tor to %s", recipientId)
                return p2pResult
            }
            Timber.d("P2P Tor send failed, trying WiFi Direct")
        }

        // Try WiFi Direct when available
        if (wifiDirectTransport.isAvailable()) {
            val wifiResult = wifiDirectTransport.sendMessage(recipientId, encryptedContent, contentType)
            if (wifiResult.isSuccess) {
                Timber.d("Sent via WiFi Direct to %s", recipientId)
                return wifiResult
            }
            Timber.d("WiFi Direct send failed, trying internet transport")
        }

        // Try internet transport
        val transport = getActiveTransport()
        val internetResult = try {
            transport.sendMessage(senderId, recipientId, encryptedContent, contentType)
        } catch (e: Exception) {
            Timber.w(e, "Internet transport failed, trying offline fallback")
            Result.failure(e)
        }

        if (internetResult.isSuccess) {
            return internetResult
        }

        // Final fallback to Bluetooth mesh
        if (bluetoothMeshTransport.isAvailable()) {
            Timber.d("Falling back to Bluetooth mesh for %s", recipientId)
            return bluetoothMeshTransport.sendMessage(recipientId, encryptedContent)
        }

        return internetResult
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
