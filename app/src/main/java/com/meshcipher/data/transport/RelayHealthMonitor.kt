package com.meshcipher.data.transport

import com.meshcipher.data.remote.api.RelayApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RM-12 / R-13: Monitors relay availability by polling the health endpoint.
 *
 * After [FAILURE_THRESHOLD] consecutive failures the relay is considered
 * [RelayHealthState.OFFLINE] and [TransportManager] will skip the relay transport
 * entirely. Automatically resets to [RelayHealthState.ONLINE] when a check succeeds.
 */
@Singleton
class RelayHealthMonitor @Inject constructor(
    private val relayApiService: RelayApiService
) {
    enum class RelayHealthState { ONLINE, DEGRADED, OFFLINE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _healthState = MutableStateFlow(RelayHealthState.ONLINE)
    val healthState: StateFlow<RelayHealthState> = _healthState.asStateFlow()

    private var consecutiveFailures = 0
    private var monitorJob: Job? = null

    fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (true) {
                check()
                delay(POLL_INTERVAL_MS)
            }
        }
        Timber.d("RelayHealthMonitor started")
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private suspend fun check() {
        try {
            val response = relayApiService.healthCheck()
            if (response.isSuccessful) {
                if (consecutiveFailures > 0) {
                    Timber.d("Relay recovered after %d failures", consecutiveFailures)
                }
                consecutiveFailures = 0
                _healthState.value = RelayHealthState.ONLINE
            } else {
                handleFailure("HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            handleFailure(e.message ?: "network error")
        }
    }

    private fun handleFailure(reason: String) {
        consecutiveFailures++
        Timber.w("Relay health check failed (%d/%d): %s", consecutiveFailures, FAILURE_THRESHOLD, reason)
        _healthState.value = when {
            consecutiveFailures >= FAILURE_THRESHOLD -> RelayHealthState.OFFLINE
            consecutiveFailures >= 1 -> RelayHealthState.DEGRADED
            else -> RelayHealthState.ONLINE
        }
    }

    /** Current state as a user-visible string. */
    fun getDisplayLabel(): String = when (_healthState.value) {
        RelayHealthState.ONLINE -> "Online"
        RelayHealthState.DEGRADED -> "Degraded"
        RelayHealthState.OFFLINE -> "Offline"
    }

    companion object {
        private const val POLL_INTERVAL_MS = 60_000L
        private const val FAILURE_THRESHOLD = 3
    }
}
