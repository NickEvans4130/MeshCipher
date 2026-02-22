package com.meshcipher.data.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.meshcipher.R
import com.meshcipher.data.bluetooth.routing.MeshRouter
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.domain.model.MeshMessage
import com.meshcipher.domain.model.Message
import com.meshcipher.domain.model.MessageStatus
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.repository.ConversationRepository
import com.meshcipher.domain.repository.MessageRepository
import com.meshcipher.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class BluetoothMeshService : Service() {

    @Inject
    lateinit var bluetoothMeshManager: BluetoothMeshManager

    @Inject
    lateinit var gattServerManager: GattServerManager

    @Inject
    lateinit var meshRouter: MeshRouter

    @Inject
    lateinit var identityManager: IdentityManager

    @Inject
    lateinit var messageRepository: MessageRepository

    @Inject
    lateinit var conversationRepository: ConversationRepository

    @Inject
    lateinit var contactRepository: ContactRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Timber.e(e, "Failed to start foreground service, stopping self")
            stopSelf()
            return
        }

        serviceScope.launch {
            startMeshNetworking()
        }

        serviceScope.launch {
            collectIncomingMessages()
        }

        serviceScope.launch {
            maintenanceLoop()
        }

        Timber.d("BluetoothMeshService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        bluetoothMeshManager.stopAdvertising()
        bluetoothMeshManager.stopScanning()
        gattServerManager.stopGattServer()
        Timber.d("BluetoothMeshService destroyed")
        super.onDestroy()
    }

    private suspend fun startMeshNetworking() {
        val advertiseResult = bluetoothMeshManager.startAdvertising()
        if (advertiseResult.isFailure) {
            Timber.e("Failed to start advertising: %s", advertiseResult.exceptionOrNull()?.message)
        }

        val scanResult = bluetoothMeshManager.startScanning()
        if (scanResult.isFailure) {
            Timber.e("Failed to start scanning: %s", scanResult.exceptionOrNull()?.message)
        }

        val gattResult = gattServerManager.startGattServer()
        if (gattResult.isFailure) {
            Timber.e("Failed to start GATT server: %s", gattResult.exceptionOrNull()?.message)
        }
    }

    private suspend fun collectIncomingMessages() {
        gattServerManager.receivedMessages.collect { message ->
            handleReceivedMessage(message)
        }
    }

    private suspend fun handleReceivedMessage(message: MeshMessage) {
        Timber.d("Received mesh message %s from %s to %s",
            message.id, message.originUserId, message.destinationUserId)

        val myIdentity = try {
            identityManager.getIdentity()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get identity")
            return
        }

        if (myIdentity == null) {
            Timber.w("No identity available, cannot process mesh message")
            return
        }

        Timber.d("My userId: %s, message destinationUserId: %s",
            myIdentity.userId, message.destinationUserId)

        // Update routing info from incoming message
        if (message.path.isNotEmpty()) {
            val fromDeviceId = message.path.last()
            meshRouter.handleIncomingMessage(message, fromDeviceId)
        }

        // Check if message is for us
        if (message.destinationUserId == myIdentity.userId) {
            Timber.d("Mesh message %s is for us! Delivering...", message.id)
            deliverMessageLocally(message)
        } else {
            Timber.d("Message not for us (dest=%s, me=%s)",
                message.destinationUserId, myIdentity.userId)
            if (message.shouldRelay()) {
                Timber.d("Relaying mesh message %s to destination %s", message.id, message.destinationUserId)
                serviceScope.launch {
                    meshRouter.routeMessage(message)
                }
            } else {
                Timber.d("Message TTL expired, not relaying")
            }
        }
    }

    private suspend fun deliverMessageLocally(meshMessage: MeshMessage) {
        try {
            // Decode the message content
            val content = String(meshMessage.encryptedPayload)

            // Find the sender contact by their user ID
            val contacts = contactRepository.getAllContacts().first()
            val senderContact = contacts.find { contact ->
                contact.id == meshMessage.originUserId
            }

            if (senderContact == null) {
                Timber.w("Received mesh message from unknown sender: %s", meshMessage.originUserId)
                return
            }

            // Get or create conversation
            val conversationId = conversationRepository.createOrGetConversation(senderContact.id)

            // Save message
            val message = Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderId = senderContact.id,
                recipientId = "me",
                content = content,
                timestamp = meshMessage.timestamp,
                status = MessageStatus.DELIVERED,
                isOwnMessage = false
            )

            messageRepository.insertMessage(message)
            conversationRepository.incrementUnreadCount(conversationId)

            // Show notification
            showMessageNotification(senderContact.displayName, content, conversationId)

            Timber.d("Delivered mesh message from %s in conversation %s",
                senderContact.displayName, conversationId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to deliver mesh message locally")
        }
    }

    private suspend fun maintenanceLoop() {
        while (serviceScope.isActive) {
            delay(MAINTENANCE_INTERVAL_MS)
            bluetoothMeshManager.removeStalePeers()
            meshRouter.cleanupStaleRoutes()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Service notification channel (low priority)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Mesh Network",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Bluetooth mesh networking status"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // Message notification channel (high priority)
            val messageChannel = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New message notifications"
                setShowBadge(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(messageChannel)
        }
    }

    private fun showMessageNotification(senderName: String, messageContent: String, conversationId: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversationId", conversationId)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setContentTitle(senderName)
            .setContentText(messageContent)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(conversationId.hashCode(), notification)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mesh Network Active")
            .setContentText("Bluetooth mesh networking is running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mesh_network"
        private const val MESSAGE_CHANNEL_ID = "messages"
        private const val MAINTENANCE_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, BluetoothMeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BluetoothMeshService::class.java))
        }
    }
}
