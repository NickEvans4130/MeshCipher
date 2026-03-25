package com.meshcipher

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import com.meshcipher.data.cleanup.MessageCleanupManager
import com.meshcipher.data.auth.CertificatePins
import com.meshcipher.data.auth.RelayAuthManager
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.data.media.MediaFileManager
import com.meshcipher.data.relay.WebSocketManager
import com.meshcipher.data.transport.P2PTransport
import com.meshcipher.data.transport.WifiDirectTransport
import com.meshcipher.data.worker.MessageCleanupWorker
import com.meshcipher.data.worker.MessageSyncWorker
import com.meshcipher.data.encryption.PreKeyManager
import com.meshcipher.domain.usecase.SafetyNumberManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class MeshCipherApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var messageCleanupManager: MessageCleanupManager

    @Inject
    lateinit var wifiDirectTransport: WifiDirectTransport

    @Inject
    lateinit var p2pTransport: P2PTransport

    @Inject
    lateinit var webSocketManager: WebSocketManager

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var relayAuthManager: RelayAuthManager

    @Inject
    lateinit var mediaFileManager: MediaFileManager

    @Inject
    lateinit var safetyNumberManager: SafetyNumberManager

    @Inject
    lateinit var preKeyManager: PreKeyManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // GAP-03 / R-04: Fail fast if certificate pins are still placeholder values in a
        // release build. See CertificatePins.kt and local.properties for configuration.
        CertificatePins.validate()

        // RM-10 / GAP-08: Ensure Kyber-1024 pre-key is generated on first run.
        preKeyManager.ensureKyberPreKey()
        preKeyManager.ensureClassicPreKeys()

        Timber.d("MeshCipher application started")

        mediaFileManager.cleanupTempFiles()
        scheduleMessageSync()
        scheduleMessageCleanup()
        setupAppLifecycleObserver()
        initializeWifiDirect()
        initializeP2PTransport()
        setupWebSocket()
        checkSafetyNumbers()
    }

    private fun initializeWifiDirect() {
        wifiDirectTransport.initialize()
        Timber.d("WiFi Direct transport initialized")
    }

    private fun initializeP2PTransport() {
        p2pTransport.initialize()
        Timber.d("P2P transport initialized")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun scheduleMessageSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<MessageSyncWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            MessageSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )

        Timber.d("Message sync worker scheduled")
    }

    private fun scheduleMessageCleanup() {
        val cleanupRequest = PeriodicWorkRequestBuilder<MessageCleanupWorker>(
            15, TimeUnit.MINUTES
        )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            MessageCleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupRequest
        )

        Timber.d("Message cleanup worker scheduled")
    }

    private fun setupWebSocket() {
        // Pre-warm auth token immediately so WebSocket connect is fast
        appScope.launch(Dispatchers.IO) {
            val userId = appPreferences.userId.firstOrNull()
            if (!userId.isNullOrBlank()) {
                Timber.d("Pre-warming auth token")
                relayAuthManager.ensureAuthenticated()
                // Connect WebSocket right away (app starts in foreground)
                Timber.d("Auth ready, connecting WebSocket")
                webSocketManager.connect(userId)
            }
        }

        // Reconnect/disconnect on foreground/background transitions
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                appScope.launch {
                    val userId = appPreferences.userId.firstOrNull()
                    if (!userId.isNullOrBlank()) {
                        Timber.d("App foregrounded, connecting WebSocket")
                        webSocketManager.connect(userId)
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                Timber.d("App backgrounded, disconnecting WebSocket")
                webSocketManager.disconnect()
                mediaFileManager.cleanupTempFiles()
            }
        })
    }

    private fun checkSafetyNumbers() {
        appScope.launch(Dispatchers.IO) {
            try {
                safetyNumberManager.checkAllSafetyNumbers()
                Timber.d("Safety number check complete")
            } catch (e: Exception) {
                Timber.w(e, "Safety number check failed")
            }
        }
    }

    private fun setupAppLifecycleObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(messageCleanupManager)
        Timber.d("App lifecycle observer registered for message cleanup")
    }
}
