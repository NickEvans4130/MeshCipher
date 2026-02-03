package com.meshcipher.data.bluetooth.routing

import java.util.concurrent.ConcurrentHashMap

class RoutingTable {

    private val entries = ConcurrentHashMap<String, RouteEntry>()

    fun addRoute(userId: String, viaDeviceId: String, hopCount: Int) {
        val current = entries[userId]
        if (current == null || current.hopCount > hopCount) {
            entries[userId] = RouteEntry(
                destinationUserId = userId,
                nextHopDeviceId = viaDeviceId,
                hopCount = hopCount,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }

    fun getRoute(userId: String): RouteEntry? {
        val entry = entries[userId] ?: return null
        if (System.currentTimeMillis() - entry.lastUpdated > ROUTE_EXPIRY_MS) {
            entries.remove(userId)
            return null
        }
        return entry
    }

    fun removeStaleRoutes() {
        val now = System.currentTimeMillis()
        entries.entries.removeIf { (_, entry) ->
            now - entry.lastUpdated > ROUTE_EXPIRY_MS
        }
    }

    fun getAllRoutes(): Map<String, RouteEntry> = entries.toMap()

    fun clear() {
        entries.clear()
    }

    companion object {
        const val ROUTE_EXPIRY_MS = 120_000L
    }
}
