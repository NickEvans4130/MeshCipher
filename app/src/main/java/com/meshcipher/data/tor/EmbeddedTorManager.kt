package com.meshcipher.data.tor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.freehaven.tor.control.TorControlConnection
import org.torproject.jni.TorService
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Proxy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmbeddedTorManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    enum class State {
        STOPPED, STARTING, BOOTSTRAPPING, CREATING_HIDDEN_SERVICE, RUNNING, ERROR
    }

    data class EmbeddedTorStatus(
        val state: State = State.STOPPED,
        val bootstrapPercent: Int = 0,
        val onionAddress: String? = null,
        val socksPort: Int = 0,
        val errorMessage: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(EmbeddedTorStatus())
    val status: StateFlow<EmbeddedTorStatus> = _status.asStateFlow()

    @Volatile
    private var torService: TorService? = null

    @Volatile
    private var torControlConnection: TorControlConnection? = null

    @Volatile
    private var serviceBound = false

    @Volatile
    private var currentOnionAddress: String? = null

    private val encryptedPrefs by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "tor_hidden_service_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(TorService.EXTRA_STATUS) ?: return
            Timber.d("TorService broadcast status: %s", status)

            when (status) {
                TorService.STATUS_ON -> {
                    scope.launch { onTorReady() }
                }
                TorService.STATUS_STARTING -> {
                    _status.value = _status.value.copy(state = State.BOOTSTRAPPING)
                }
                TorService.STATUS_OFF -> {
                    _status.value = EmbeddedTorStatus(state = State.STOPPED)
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceBound = true
            val localBinder = binder as? TorService.LocalBinder
            torService = localBinder?.service
            Timber.d("TorService connected, service instance: %s", torService != null)

            if (torService != null) {
                scope.launch { waitForControlConnection() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            torService = null
            torControlConnection = null
            Timber.d("TorService disconnected")
            _status.value = EmbeddedTorStatus(state = State.STOPPED)
        }
    }

    fun start() {
        if (_status.value.state != State.STOPPED && _status.value.state != State.ERROR) {
            Timber.d("Tor already starting or running")
            return
        }

        _status.value = EmbeddedTorStatus(state = State.STARTING)

        try {
            // Register for TorService status broadcasts
            LocalBroadcastManager.getInstance(context).registerReceiver(
                statusReceiver,
                IntentFilter(TorService.ACTION_STATUS)
            )

            val intent = Intent(context, TorService::class.java)
            intent.action = TorService.ACTION_START
            context.startService(intent)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Timber.d("TorService bind requested")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start TorService")
            _status.value = EmbeddedTorStatus(
                state = State.ERROR,
                errorMessage = "Failed to start Tor: ${e.message}"
            )
        }
    }

    fun stop() {
        scope.launch {
            try {
                removeHiddenService()
            } catch (e: Exception) {
                Timber.w(e, "Error removing hidden service during shutdown")
            }

            torControlConnection = null

            try {
                LocalBroadcastManager.getInstance(context).unregisterReceiver(statusReceiver)
            } catch (e: Exception) {
                Timber.w(e, "Error unregistering status receiver")
            }

            try {
                if (serviceBound) {
                    context.unbindService(serviceConnection)
                    serviceBound = false
                }
                val stopIntent = Intent(context, TorService::class.java)
                stopIntent.action = TorService.ACTION_STOP
                context.startService(stopIntent)
            } catch (e: Exception) {
                Timber.w(e, "Error stopping TorService")
            }

            torService = null
            currentOnionAddress = null
            _status.value = EmbeddedTorStatus(state = State.STOPPED)
            Timber.d("Embedded Tor stopped")
        }
    }

    private suspend fun waitForControlConnection() {
        _status.value = _status.value.copy(state = State.BOOTSTRAPPING)

        // Poll TorService for the TorControlConnection
        var attempts = 0
        while (attempts < 120) {
            delay(1000)
            attempts++

            val service = torService ?: break
            val conn = try {
                service.torControlConnection
            } catch (e: Exception) {
                Timber.d("Control connection not ready yet (attempt %d)", attempts)
                null
            }

            if (conn != null) {
                torControlConnection = conn
                Timber.d("Got TorControlConnection from TorService after %d seconds", attempts)
                monitorBootstrap(conn, service)
                return
            }
        }

        if (torControlConnection == null) {
            _status.value = EmbeddedTorStatus(
                state = State.ERROR,
                errorMessage = "Tor control connection not available"
            )
        }
    }

    private suspend fun onTorReady() {
        val service = torService ?: return
        val conn = try {
            service.torControlConnection
        } catch (e: Exception) {
            null
        }

        if (conn != null && torControlConnection == null) {
            torControlConnection = conn
        }

        val socksPort = try {
            service.socksPort
        } catch (e: Exception) {
            0
        }

        if (socksPort > 0) {
            _status.value = _status.value.copy(
                bootstrapPercent = 100,
                socksPort = socksPort
            )
            Timber.d("Tor ready via broadcast, SOCKS port: %d", socksPort)
        }
    }

    private suspend fun monitorBootstrap(conn: TorControlConnection, service: TorService) {
        try {
            // Poll for bootstrap completion
            var bootstrapped = false
            var pollAttempts = 0
            while (!bootstrapped && pollAttempts < 120) {
                delay(1000)
                pollAttempts++
                try {
                    val info = conn.getInfo("status/bootstrap-phase") ?: ""
                    val progress = Regex("PROGRESS=(\\d+)").find(info)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    // Only update bootstrap percent if state hasn't advanced past BOOTSTRAPPING
                    val curState = _status.value.state
                    if (curState == State.STARTING || curState == State.BOOTSTRAPPING) {
                        _status.value = _status.value.copy(bootstrapPercent = progress)
                    }
                    Timber.d("Tor bootstrap: %d%%", progress)

                    if (progress >= 100) {
                        bootstrapped = true
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to poll bootstrap status")
                }
            }

            if (!bootstrapped) {
                _status.value = EmbeddedTorStatus(
                    state = State.ERROR,
                    errorMessage = "Tor bootstrap timed out"
                )
                return
            }

            // Get SOCKS port from TorService directly
            val socksPort = try {
                val port = service.socksPort
                if (port > 0) port else {
                    val listeners = conn.getInfo("net/listeners/socks") ?: ""
                    Regex(":(\\d+)").find(listeners)?.groupValues?.get(1)?.toIntOrNull() ?: 9050
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to get SOCKS port, using default")
                9050
            }

            // Only update state if it hasn't advanced past BOOTSTRAPPING
            // (createHiddenService may have already moved state forward)
            val finalState = _status.value.state
            if (finalState == State.STARTING || finalState == State.BOOTSTRAPPING) {
                _status.value = _status.value.copy(
                    state = State.BOOTSTRAPPING,
                    bootstrapPercent = 100,
                    socksPort = socksPort
                )
            } else if (_status.value.socksPort == 0 && socksPort > 0) {
                _status.value = _status.value.copy(socksPort = socksPort)
            }

            Timber.d("Tor fully bootstrapped, SOCKS port: %d", socksPort)
        } catch (e: Exception) {
            Timber.e(e, "Error monitoring bootstrap")
            _status.value = EmbeddedTorStatus(
                state = State.ERROR,
                errorMessage = "Bootstrap monitoring error: ${e.message}"
            )
        }
    }

    suspend fun createHiddenService(localPort: Int) {
        val conn = torControlConnection
        if (conn == null) {
            Timber.e("No control connection for hidden service creation")
            _status.value = _status.value.copy(
                state = State.ERROR,
                errorMessage = "No Tor control connection"
            )
            return
        }

        _status.value = _status.value.copy(state = State.CREATING_HIDDEN_SERVICE)

        try {
            val savedKey = encryptedPrefs.getString("hs_private_key", null)

            val portMapping: Map<Int, String> = mapOf(80 to "127.0.0.1:$localPort")
            // jtorctl addOnion returns keys: "onionAddress", "onionPrivKey"
            val result: Map<String, String> = if (savedKey != null) {
                conn.addOnion(savedKey, portMapping)
            } else {
                conn.addOnion("NEW:ED25519-V3", portMapping)
            }

            Timber.d("addOnion result keys: %s", result.keys)

            val onionHost = result["onionAddress"]
            val privateKey = result["onionPrivKey"]

            if (onionHost == null) {
                _status.value = _status.value.copy(
                    state = State.ERROR,
                    errorMessage = "Failed to get hidden service ID"
                )
                return
            }

            // Save private key for persistent .onion across restarts
            // jtorctl returns the raw key; we must prefix it for reuse
            if (privateKey != null && savedKey == null) {
                val keyToSave = if (privateKey.contains(":")) {
                    privateKey
                } else {
                    "ED25519-V3:$privateKey"
                }
                encryptedPrefs.edit().putString("hs_private_key", keyToSave).apply()
                Timber.d("Saved hidden service private key")
            }

            val onionAddr = "$onionHost.onion"
            currentOnionAddress = onionAddr

            _status.value = _status.value.copy(
                state = State.RUNNING,
                onionAddress = onionAddr
            )

            Timber.d("Hidden service created: %s", onionAddr)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create hidden service")
            _status.value = _status.value.copy(
                state = State.ERROR,
                errorMessage = "Hidden service error: ${e.message}"
            )
        }
    }

    private fun removeHiddenService() {
        try {
            val onionAddr = currentOnionAddress ?: return
            val serviceId = onionAddr.removeSuffix(".onion")
            torControlConnection?.delOnion(serviceId)
            Timber.d("Removed hidden service: %s", onionAddr)
        } catch (e: Exception) {
            Timber.w(e, "Failed to remove hidden service")
        }
    }

    fun getSocksProxy(): Proxy {
        val port = _status.value.socksPort.takeIf { it > 0 } ?: 9050
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
    }

    fun getOnionAddress(): String? = currentOnionAddress

    fun isRunning(): Boolean = _status.value.state == State.RUNNING
}
