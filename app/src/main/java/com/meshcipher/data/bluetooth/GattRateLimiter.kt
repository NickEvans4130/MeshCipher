package com.meshcipher.data.bluetooth

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * R-14: Per-peer GATT write rate limiter.
 *
 * Allows at most [maxMessages] writes per [windowMs] from a single remote device.
 * If a peer exceeds this limit it is placed on a blocklist for [blockDurationMs] and all further
 * writes are silently dropped until the block expires.
 *
 * All state is in-memory; it resets when the GATT server restarts.
 */
class GattRateLimiter(
    private val maxMessages: Int = MAX_MESSAGES_PER_WINDOW,
    private val windowMs: Long = WINDOW_MS,
    private val blockDurationMs: Long = BLOCK_DURATION_MS
) {
    private data class PeerState(
        val timestamps: ArrayDeque<Long> = ArrayDeque(),
        var blockedUntil: Long = 0L
    )

    private val peers = ConcurrentHashMap<String, PeerState>()

    /**
     * Returns true if the write from [address] is permitted, false if it should be dropped.
     * Thread-safe: each peer state is synchronised on its own object.
     */
    fun allow(address: String): Boolean {
        val state = peers.getOrPut(address) { PeerState() }
        synchronized(state) {
            val now = System.currentTimeMillis()

            // Check active block first.
            if (state.blockedUntil > now) {
                Timber.w("GattRateLimiter: dropping write from blocked peer %s (unblocks in %d s)",
                    address, (state.blockedUntil - now) / 1000)
                return false
            }

            // Slide the window: remove timestamps older than [windowMs].
            while (state.timestamps.isNotEmpty() && now - state.timestamps.first() > windowMs) {
                state.timestamps.removeFirst()
            }

            if (state.timestamps.size >= maxMessages) {
                // Rate limit exceeded — block the peer.
                state.blockedUntil = now + blockDurationMs
                state.timestamps.clear()
                Timber.w(
                    "GattRateLimiter: peer %s exceeded %d msgs/%d ms — blocked for %d min",
                    address, maxMessages, windowMs, blockDurationMs / 60_000
                )
                return false
            }

            state.timestamps.addLast(now)
            return true
        }
    }

    /** Evicts state for peers whose block has expired and who have no recent activity. */
    fun clearStale() {
        val now = System.currentTimeMillis()
        peers.entries.removeIf { (_, state) ->
            synchronized(state) {
                state.blockedUntil < now && state.timestamps.isEmpty()
            }
        }
    }

    companion object {
        const val MAX_MESSAGES_PER_WINDOW = 10
        const val WINDOW_MS = 5_000L           // 5 seconds
        const val BLOCK_DURATION_MS = 5 * 60 * 1000L // 5 minutes
    }
}
