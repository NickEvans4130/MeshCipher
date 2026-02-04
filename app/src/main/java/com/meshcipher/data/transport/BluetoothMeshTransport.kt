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
        if (!isAvailable()) {
            return Result.failure(Exception("Bluetooth mesh not available"))
        }

        val identity = identityManager.getIdentity()
            ?: return Result.failure(Exception("No identity"))

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

        val routeResult = meshRouter.routeMessage(meshMessage)
        return if (routeResult.isSuccess) {
            Timber.d("Mesh message %s routed for %s", messageId, recipientUserId)
            Result.success(messageId)
        } else {
            Timber.e("Mesh routing failed for %s: %s",
                messageId, routeResult.exceptionOrNull()?.message)
            Result.failure(routeResult.exceptionOrNull() ?: Exception("Mesh routing failed"))
        }
    }

    fun isAvailable(): Boolean {
        return bluetoothMeshManager.isBluetoothEnabled() &&
                bluetoothMeshManager.discoveredPeers.value.isNotEmpty()
    }

    fun isRecipientReachable(recipientUserId: String): Boolean {
        return meshRouter.canReach(recipientUserId)
    }
}
