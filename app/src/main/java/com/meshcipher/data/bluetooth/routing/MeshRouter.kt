package com.meshcipher.data.bluetooth.routing

import com.meshcipher.data.bluetooth.BluetoothMeshManager
import com.meshcipher.data.bluetooth.GattClientManager
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.domain.model.MeshMessage
import com.meshcipher.domain.model.MeshPeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshRouter @Inject constructor(
    private val bluetoothMeshManager: BluetoothMeshManager,
    private val gattClientManager: GattClientManager,
    private val identityManager: IdentityManager
) {
    val routingTable = RoutingTable()

    private val seenMessages: MutableSet<String> =
        Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>(MAX_SEEN_CACHE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > MAX_SEEN_CACHE
            }
        })

    suspend fun routeMessage(message: MeshMessage): Result<Unit> = withContext(Dispatchers.IO) {
        // Loop prevention
        synchronized(seenMessages) {
            if (seenMessages.contains(message.id)) {
                Timber.d("Message %s already seen, dropping", message.id)
                return@withContext Result.success(Unit)
            }
            seenMessages.add(message.id)
        }

        // TTL check
        if (!message.shouldRelay()) {
            Timber.d("Message %s TTL expired (hop %d/%d)", message.id, message.hopCount, message.ttl)
            return@withContext Result.failure(Exception("TTL expired"))
        }

        val myDeviceId = getMyDeviceId()

        // Check if message is for a direct neighbor
        val route = routingTable.getRoute(message.destinationUserId)

        if (route != null) {
            val nextHop = findPeerByDeviceId(route.nextHopDeviceId)
            if (nextHop != null) {
                Timber.d("Routing message %s via %s (%d hops)",
                    message.id, nextHop.deviceId, route.hopCount)
                return@withContext sendToPeer(nextHop, message.incrementHop(myDeviceId))
            }
            Timber.w("Next hop %s not reachable, falling back to flood", route.nextHopDeviceId)
        }

        // No route or next hop unreachable: flood to all direct neighbors
        val allPeers = bluetoothMeshManager.discoveredPeers.value
        Timber.d("All discovered peers: %d", allPeers.size)
        allPeers.forEach { peer ->
            Timber.d("  Peer: %s, inRange=%s, hopCount=%d, lastSeen=%d ms ago",
                peer.bluetoothAddress, peer.isInRange, peer.hopCount,
                System.currentTimeMillis() - peer.lastSeen)
        }

        val neighbors = getDirectNeighbors()
        if (neighbors.isEmpty()) {
            Timber.w("No neighbors in range for message %s (total peers: %d)", message.id, allPeers.size)
            return@withContext Result.failure(Exception("No neighbors in range"))
        }

        Timber.d("Flooding message %s to %d neighbors", message.id, neighbors.size)
        val forwarded = message.incrementHop(myDeviceId)
        var lastFailure: Exception? = null
        var anySuccess = false

        for (neighbor in neighbors) {
            // Don't send back to origin or nodes already in path
            if (neighbor.deviceId == message.originDeviceId ||
                forwarded.path.contains(neighbor.deviceId)
            ) {
                Timber.d("Skipping neighbor %s (origin or in path)", neighbor.bluetoothAddress)
                continue
            }

            Timber.d("Sending to neighbor %s", neighbor.bluetoothAddress)
            val result = sendToPeer(neighbor, forwarded)
            if (result.isSuccess) {
                anySuccess = true
            } else {
                lastFailure = result.exceptionOrNull() as? Exception
            }
        }

        if (anySuccess) {
            Result.success(Unit)
        } else {
            Result.failure(lastFailure ?: Exception("Failed to forward to any neighbor"))
        }
    }

    fun handleIncomingMessage(message: MeshMessage, fromDeviceId: String) {
        // Update routing table from incoming message
        if (message.path.isNotEmpty()) {
            val originUserId = message.originDeviceId
            updateRoute(originUserId, fromDeviceId, message.hopCount)
        }
    }

    fun canReach(userId: String): Boolean {
        // Check direct neighbors
        val peers = bluetoothMeshManager.discoveredPeers.value
        if (peers.any { it.userId == userId && it.isInRange }) return true

        // Check routing table
        return routingTable.getRoute(userId) != null
    }

    fun updateRoute(userId: String, viaDeviceId: String, hopCount: Int) {
        routingTable.addRoute(userId, viaDeviceId, hopCount)
        Timber.d("Route updated: %s via %s (%d hops)", userId, viaDeviceId, hopCount)
    }

    fun cleanupStaleRoutes() {
        routingTable.removeStaleRoutes()
    }

    private fun getDirectNeighbors(): List<MeshPeer> {
        return bluetoothMeshManager.discoveredPeers.value.filter { it.isInRange }
    }

    private fun findPeerByDeviceId(deviceId: String): MeshPeer? {
        return bluetoothMeshManager.discoveredPeers.value.find { it.deviceId == deviceId }
    }

    private suspend fun sendToPeer(peer: MeshPeer, message: MeshMessage): Result<Unit> {
        return try {
            gattClientManager.sendMessage(peer.bluetoothAddress, message)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send message %s to peer %s", message.id, peer.deviceId)
            Result.failure(e)
        }
    }

    private suspend fun getMyDeviceId(): String {
        return identityManager.getIdentity()?.deviceId ?: "unknown"
    }

    companion object {
        private const val MAX_SEEN_CACHE = 1000
    }
}
