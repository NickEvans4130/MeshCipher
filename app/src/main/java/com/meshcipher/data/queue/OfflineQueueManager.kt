package com.meshcipher.data.queue

import com.meshcipher.data.network.NetworkMonitor
import com.meshcipher.data.transport.SmartModeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds messages that could not be delivered because no transport was available.
 *
 * When internet connectivity returns, [retryTrigger] emits so that callers
 * (e.g. ChatViewModel) can iterate over pending conversations and retry sends.
 * The actual retransmission is intentionally left to the caller to avoid a
 * circular dependency on TransportManager.
 */
@Singleton
class OfflineQueueManager @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    private val smartModeManager: SmartModeManager
) {
    data class QueuedSend(
        val recipientId: String,
        val conversationId: String,
        val messageId: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val queue = mutableListOf<QueuedSend>()

    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize

    /** Emits a Unit every time connectivity is restored and the queue is non-empty. */
    private val _retryTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val retryTrigger: SharedFlow<Unit> = _retryTrigger

    init {
        scope.launch {
            networkMonitor.isConnected.collect { connected ->
                if (connected && queue.isNotEmpty()) {
                    Timber.d("OfflineQueue: connectivity restored, signalling retry (%d queued)", queue.size)
                    _retryTrigger.tryEmit(Unit)
                }
            }
        }
    }

    fun enqueue(recipientId: String, conversationId: String, messageId: String) {
        synchronized(queue) {
            queue.add(QueuedSend(recipientId, conversationId, messageId))
            _queueSize.value = queue.size
        }
        smartModeManager.reportTransportUsed(SmartModeManager.ActiveTransport.QUEUED)
        Timber.d("OfflineQueue: enqueued message %s for %s (total=%d)", messageId, recipientId, queue.size)
    }

    fun dequeue(messageId: String) {
        synchronized(queue) {
            queue.removeAll { it.messageId == messageId }
            _queueSize.value = queue.size
        }
    }

    fun getQueue(): List<QueuedSend> = synchronized(queue) { queue.toList() }

    fun clearQueue() {
        synchronized(queue) {
            queue.clear()
            _queueSize.value = 0
        }
    }

    fun pendingCountFor(recipientId: String): Int =
        synchronized(queue) { queue.count { it.recipientId == recipientId } }
}
