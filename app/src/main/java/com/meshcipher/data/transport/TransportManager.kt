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
    private val bluetoothMeshTransport: BluetoothMeshTransport
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

    fun isMeshAvailable(): Boolean = bluetoothMeshTransport.isAvailable()

    suspend fun sendWithFallback(
        senderId: String,
        recipientId: String,
        encryptedContent: ByteArray
    ): Result<String> {
        val mode = currentMode.get()

        // P2P_ONLY: mesh only
        if (mode == ConnectionMode.P2P_ONLY) {
            return bluetoothMeshTransport.sendMessage(recipientId, encryptedContent)
        }

        // Try primary internet transport first
        val transport = getActiveTransport()
        val internetResult = try {
            transport.sendMessage(senderId, recipientId, encryptedContent)
        } catch (e: Exception) {
            Timber.w(e, "Internet transport failed, trying mesh fallback")
            Result.failure(e)
        }

        if (internetResult.isSuccess) {
            return internetResult
        }

        // Fallback to Bluetooth mesh
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
