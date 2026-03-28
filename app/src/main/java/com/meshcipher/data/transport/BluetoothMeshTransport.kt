package com.meshcipher.data.transport

import com.meshcipher.data.bluetooth.BluetoothMeshManager
import com.meshcipher.data.bluetooth.GattServerManager
import com.meshcipher.data.bluetooth.routing.MeshRouter
import com.meshcipher.data.encryption.SignalProtocolStoreImpl
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.routing.OnionBuilder
import com.meshcipher.domain.model.MeshMessage
import com.meshcipher.domain.repository.PrivacyProfileRepository
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothMeshTransport @Inject constructor(
    private val bluetoothMeshManager: BluetoothMeshManager,
    private val gattServerManager: GattServerManager,
    private val meshRouter: MeshRouter,
    private val identityManager: IdentityManager,
    private val privacyProfileRepository: PrivacyProfileRepository,
    private val signalProtocolStore: SignalProtocolStoreImpl
) {

    val incomingMessages: SharedFlow<MeshMessage> = gattServerManager.receivedMessages

    suspend fun sendMessage(
        recipientUserId: String,
        encryptedContent: ByteArray
    ): Result<String> {
        val btEnabled = bluetoothMeshManager.isBluetoothEnabled()
        val peers = bluetoothMeshManager.discoveredPeers.value
        Timber.d("Mesh send: BT enabled=%s, peers=%d, recipient=%s",
            btEnabled, peers.size, recipientUserId)

        if (!btEnabled) {
            return Result.failure(Exception("Bluetooth not enabled"))
        }
        if (peers.isEmpty()) {
            return Result.failure(Exception("No mesh peers discovered"))
        }

        val identity = identityManager.getIdentity()
            ?: return Result.failure(Exception("No identity"))

        Timber.d("Sending from user %s to %s", identity.userId, recipientUserId)

        val messageId = UUID.randomUUID().toString()

        // MD-04: build onion routing header when privacy profile is HIGH_PRIVACY/MAXIMUM
        // and a multi-hop route exists.  Falls back to PLAINTEXT silently if route is
        // incomplete or key material is unavailable.
        val profile = privacyProfileRepository.getPrivacyProfile()
        var routingMode = RoutingMode.PLAINTEXT
        var onionHeader: OnionRoutingHeader? = null
        if (profile.isEnhanced()) {
            val allPeers = bluetoothMeshManager.discoveredPeers.value
            val peerByDeviceId = allPeers.associateBy { it.deviceId }
            val destPeer = allPeers.firstOrNull { it.userId == recipientUserId }
            if (destPeer != null) {
                // Walk the routing table to build the full ordered relay list:
                // start from recipientUserId and follow successive nextHopDeviceId entries
                // back toward ourselves, building the path in sender-first order.
                val relayPeers = mutableListOf<com.meshcipher.domain.model.MeshPeer>()
                val visitedUserIds = mutableSetOf(recipientUserId)
                var currentUserId = recipientUserId
                repeat(OnionBuilder.ONION_MAX_HOPS) {
                    val hop = meshRouter.routingTable.getRoute(currentUserId) ?: return@repeat
                    val hopPeer = peerByDeviceId[hop.nextHopDeviceId] ?: return@repeat
                    if (!visitedUserIds.add(hopPeer.userId)) return@repeat  // loop guard
                    relayPeers.add(0, hopPeer)  // prepend → sender-first order
                    currentUserId = hopPeer.userId
                }
                val built = OnionBuilder.buildOnion(relayPeers, destPeer, signalProtocolStore)
                if (built != null) {
                    routingMode = RoutingMode.ONION
                    onionHeader = built
                    Timber.d("MD-04: built onion for %s via %d relay(s)", recipientUserId, relayPeers.size)
                } else {
                    Timber.d("MD-04: onion build failed, falling back to PLAINTEXT routing")
                }
            } else {
                Timber.d("MD-04: destination peer not discovered, falling back to PLAINTEXT")
            }
        }

        val meshMessage = MeshMessage(
            id = messageId,
            originDeviceId = identity.deviceId,
            originUserId = identity.userId,
            destinationUserId = recipientUserId,
            encryptedPayload = encryptedContent,
            timestamp = System.currentTimeMillis(),
            ttl = MeshMessage.MAX_TTL,
            hopCount = 0,
            routingMode = routingMode,
            onionHeader = onionHeader
        )

        Timber.d("Routing mesh message %s (%d bytes)", messageId, encryptedContent.size)
        val routeResult = meshRouter.routeMessage(meshMessage)
        return if (routeResult.isSuccess) {
            Timber.d("Mesh message %s routed successfully", messageId)
            Result.success(messageId)
        } else {
            val error = routeResult.exceptionOrNull()?.message ?: "Unknown error"
            Timber.e("Mesh routing failed for %s: %s", messageId, error)
            Result.failure(routeResult.exceptionOrNull() ?: Exception("Mesh routing failed: $error"))
        }
    }

    fun isAvailable(): Boolean {
        val btEnabled = bluetoothMeshManager.isBluetoothEnabled()
        val hasPeers = bluetoothMeshManager.discoveredPeers.value.isNotEmpty()
        return btEnabled && hasPeers
    }

    fun isRecipientReachable(recipientUserId: String): Boolean {
        return meshRouter.canReach(recipientUserId)
    }
}
