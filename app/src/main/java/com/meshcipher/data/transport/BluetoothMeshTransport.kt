package com.meshcipher.data.transport

import com.meshcipher.data.bluetooth.BluetoothMeshManager
import com.meshcipher.data.bluetooth.GattServerManager
import com.meshcipher.data.bluetooth.routing.MeshRouter
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.domain.model.MeshMessage
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
    private val identityManager: IdentityManager
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
        val meshMessage = MeshMessage(
            id = messageId,
            originDeviceId = identity.deviceId,
            originUserId = identity.userId,
            destinationUserId = recipientUserId,
            encryptedPayload = encryptedContent,
            timestamp = System.currentTimeMillis(),
            ttl = MeshMessage.MAX_TTL,
            hopCount = 0
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
