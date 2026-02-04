# MeshCipher - Phase 3: Internet Transport
## Weeks 7-10: Message Sending, Relay Server, Real Communication

---

## Phase 3 Overview

**Goals:**
- Enable ACTUAL message sending/receiving
- Build and deploy relay server to ThinkPad
- Implement message queuing for offline users
- Add delivery status indicators
- Real-time message synchronization

**Deliverables:**
- Messages send and receive over internet (E2E encrypted)
- Relay server running on home ThinkPad
- Message queue for offline recipients
- Delivery receipts (sent/delivered/read)
- Background message synchronization
- Working end-to-end messaging system

**Prerequisites:**
- ✅ Phase 1 complete (database, encryption working)
- ✅ Phase 2 complete (UI functional)
- ✅ ThinkPad running Ubuntu Server with internet access

---

## Updated build.gradle.kts

Add these dependencies to app/build.gradle.kts:

```kotlin
dependencies {
    // ... existing Phase 1 & 2 dependencies
    
    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // WorkManager (background sync)
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
```

---

## Relay Server (Python Flask)

### Complete server.py

```python
#!/usr/bin/env python3
from flask import Flask, request, jsonify
from flask_sqlalchemy import SQLAlchemy
from datetime import datetime
import os
import logging

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Flask(__name__)
app.config['SQLALCHEMY_DATABASE_URI'] = os.environ.get(
    'DATABASE_URL',
    'postgresql://meshcipher:password@localhost/meshcipher'
)
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False

db = SQLAlchemy(app)

# Models
class QueuedMessage(db.Model):
    __tablename__ = 'queued_messages'
    
    id = db.Column(db.Integer, primary_key=True)
    message_id = db.Column(db.String(36), unique=True, nullable=False, index=True)
    sender_id = db.Column(db.String(100), nullable=False, index=True)
    recipient_id = db.Column(db.String(100), nullable=False, index=True)
    encrypted_payload = db.Column(db.Text, nullable=False)
    timestamp = db.Column(db.BigInteger, nullable=False)
    queued_at = db.Column(db.BigInteger, nullable=False)
    delivered = db.Column(db.Boolean, default=False, index=True)
    
    def to_dict(self):
        return {
            'message_id': self.message_id,
            'sender_id': self.sender_id,
            'encrypted_payload': self.encrypted_payload,
            'timestamp': self.timestamp,
            'queued_at': self.queued_at
        }

# API Routes
@app.route('/api/v1/health', methods=['GET'])
def health_check():
    """Health check endpoint"""
    try:
        # Test database connection
        db.session.execute('SELECT 1')
        return jsonify({
            'status': 'healthy',
            'database': 'connected',
            'timestamp': int(datetime.utcnow().timestamp() * 1000)
        }), 200
    except Exception as e:
        logger.error(f"Health check failed: {e}")
        return jsonify({
            'status': 'unhealthy',
            'error': str(e)
        }), 500

@app.route('/api/v1/relay/message', methods=['POST'])
def relay_message():
    """Receive and queue a message"""
    try:
        data = request.json
        
        # Validate required fields
        required_fields = ['recipient_id', 'encrypted_payload', 'message_id', 'timestamp']
        for field in required_fields:
            if field not in data:
                return jsonify({'error': f'Missing field: {field}'}), 400
        
        # Extract sender_id from auth header or use 'unknown'
        auth_header = request.headers.get('Authorization', '')
        sender_id = data.get('sender_id', 'unknown')
        
        # Create queued message
        queued_message = QueuedMessage(
            message_id=data['message_id'],
            sender_id=sender_id,
            recipient_id=data['recipient_id'],
            encrypted_payload=data['encrypted_payload'],
            timestamp=data['timestamp'],
            queued_at=int(datetime.utcnow().timestamp() * 1000),
            delivered=False
        )
        
        db.session.add(queued_message)
        db.session.commit()
        
        logger.info(f"Message queued: {data['message_id']} from {sender_id} to {data['recipient_id']}")
        
        return jsonify({
            'status': 'queued',
            'message_id': data['message_id'],
            'queued_at': queued_message.queued_at
        }), 200
        
    except Exception as e:
        logger.error(f"Error queuing message: {e}")
        db.session.rollback()
        return jsonify({'error': str(e)}), 500

@app.route('/api/v1/relay/messages', methods=['GET'])
def get_queued_messages():
    """Get queued messages for a recipient"""
    try:
        # Get recipient_id from query parameter
        recipient_id = request.args.get('recipient_id')
        
        if not recipient_id:
            return jsonify({'error': 'recipient_id required'}), 400
        
        # Get all undelivered messages for this recipient
        messages = QueuedMessage.query.filter_by(
            recipient_id=recipient_id,
            delivered=False
        ).order_by(QueuedMessage.queued_at).all()
        
        result = [msg.to_dict() for msg in messages]
        
        # Mark as delivered
        for msg in messages:
            msg.delivered = True
        
        db.session.commit()
        
        logger.info(f"Delivered {len(result)} messages to {recipient_id}")
        
        return jsonify({
            'messages': result,
            'count': len(result)
        }), 200
        
    except Exception as e:
        logger.error(f"Error fetching messages: {e}")
        db.session.rollback()
        return jsonify({'error': str(e)}), 500

@app.route('/api/v1/stats', methods=['GET'])
def get_stats():
    """Get server statistics"""
    try:
        total_messages = QueuedMessage.query.count()
        delivered_messages = QueuedMessage.query.filter_by(delivered=True).count()
        pending_messages = QueuedMessage.query.filter_by(delivered=False).count()
        
        return jsonify({
            'total_messages': total_messages,
            'delivered_messages': delivered_messages,
            'pending_messages': pending_messages
        }), 200
    except Exception as e:
        logger.error(f"Error getting stats: {e}")
        return jsonify({'error': str(e)}), 500

# Error handlers
@app.errorhandler(404)
def not_found(error):
    return jsonify({'error': 'Not found'}), 404

@app.errorhandler(500)
def internal_error(error):
    logger.error(f"Internal error: {error}")
    return jsonify({'error': 'Internal server error'}), 500

if __name__ == '__main__':
    # Create tables
    with app.app_context():
        db.create_all()
        logger.info("Database tables created")
    
    # Run server
    port = int(os.environ.get('PORT', 5000))
    app.run(host='0.0.0.0', port=port, debug=False)
```

### requirements.txt

```
Flask==3.0.0
Flask-SQLAlchemy==3.1.1
psycopg2-binary==2.9.9
gunicorn==21.2.0
```

### Installation Script

```bash
#!/bin/bash
# install-relay.sh

# Update system
sudo apt update
sudo apt upgrade -y

# Install PostgreSQL
sudo apt install -y postgresql postgresql-contrib

# Setup PostgreSQL database
sudo -u postgres psql << EOF
CREATE DATABASE meshcipher;
CREATE USER meshcipher WITH PASSWORD 'your_secure_password_here';
GRANT ALL PRIVILEGES ON DATABASE meshcipher TO meshcipher;
ALTER DATABASE meshcipher OWNER TO meshcipher;
\q
EOF

# Install Python3 and pip
sudo apt install -y python3 python3-pip python3-venv

# Create project directory
mkdir -p ~/meshcipher-relay
cd ~/meshcipher-relay

# Create virtual environment
python3 -m venv venv
source venv/bin/activate

# Install dependencies
pip install -r requirements.txt

echo "Installation complete! Run with: python3 server.py"
```

### systemd Service

```ini
# /etc/systemd/system/meshcipher-relay.service
[Unit]
Description=MeshCipher Relay Server
After=network.target postgresql.service
Wants=postgresql.service

[Service]
Type=simple
User=nick
WorkingDirectory=/home/nick/meshcipher-relay
Environment="PATH=/home/nick/meshcipher-relay/venv/bin"
Environment="DATABASE_URL=postgresql://meshcipher:password@localhost/meshcipher"
ExecStart=/home/nick/meshcipher-relay/venv/bin/gunicorn --bind 0.0.0.0:5000 --workers 4 server:app
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable service:
```bash
sudo systemctl daemon-reload
sudo systemctl enable meshcipher-relay
sudo systemctl start meshcipher-relay
sudo systemctl status meshcipher-relay
```

---

## Android Code - Network API

### RelayApiModels.kt

```kotlin
package com.meshcipher.data.remote.dto

import com.google.gson.annotations.SerializedName

data class RelayMessageRequest(
    @SerializedName("recipient_id")
    val recipientId: String,
    @SerializedName("encrypted_payload")
    val encryptedPayload: String,
    @SerializedName("message_id")
    val messageId: String,
    @SerializedName("timestamp")
    val timestamp: Long,
    @SerializedName("sender_id")
    val senderId: String
)

data class RelayMessageResponse(
    @SerializedName("status")
    val status: String,
    @SerializedName("message_id")
    val messageId: String,
    @SerializedName("queued_at")
    val queuedAt: Long
)

data class QueuedMessageDto(
    @SerializedName("message_id")
    val messageId: String,
    @SerializedName("sender_id")
    val senderId: String,
    @SerializedName("encrypted_payload")
    val encryptedPayload: String,
    @SerializedName("timestamp")
    val timestamp: Long,
    @SerializedName("queued_at")
    val queuedAt: Long
)

data class QueuedMessagesResponse(
    @SerializedName("messages")
    val messages: List<QueuedMessageDto>,
    @SerializedName("count")
    val count: Int
)
```

### RelayApiService.kt

```kotlin
package com.meshcipher.data.remote.api

import com.meshcipher.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

interface RelayApiService {
    
    @POST("api/v1/relay/message")
    suspend fun relayMessage(
        @Body request: RelayMessageRequest
    ): Response<RelayMessageResponse>
    
    @GET("api/v1/relay/messages")
    suspend fun getQueuedMessages(
        @Query("recipient_id") recipientId: String
    ): Response<QueuedMessagesResponse>
    
    @GET("api/v1/health")
    suspend fun healthCheck(): Response<Map<String, Any>>
}
```

---

## Transport Layer

### InternetTransport.kt

```kotlin
package com.meshcipher.data.transport

import android.content.Context
import android.util.Base64
import com.meshcipher.data.remote.api.RelayApiService
import com.meshcipher.data.remote.dto.RelayMessageRequest
import com.meshcipher.domain.model.Contact
import com.meshcipher.util.NetworkUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class EncryptedMessage(
    val id: String,
    val senderId: String,
    val recipientId: String,
    val ciphertext: ByteArray,
    val timestamp: Long
)

sealed class SendResult {
    data class Success(val method: String) : SendResult()
    object Queued : SendResult()
    data class Failed(val error: String) : SendResult()
}

@Singleton
class InternetTransport @Inject constructor(
    private val context: Context,
    private val relayApi: RelayApiService
) {
    private var userId: String = "me" // TODO: Get from preferences
    
    suspend fun send(
        message: EncryptedMessage,
        recipient: Contact
    ): SendResult {
        if (!NetworkUtils.isInternetAvailable(context)) {
            return SendResult.Failed("No internet connection")
        }
        
        return try {
            val request = RelayMessageRequest(
                recipientId = recipient.id,
                encryptedPayload = Base64.encodeToString(message.ciphertext, Base64.NO_WRAP),
                messageId = message.id,
                timestamp = message.timestamp,
                senderId = userId
            )
            
            val response = relayApi.relayMessage(request)
            
            if (response.isSuccessful) {
                val body = response.body()!!
                Timber.d("Message sent successfully: ${body.messageId}")
                SendResult.Queued
            } else {
                Timber.e("Failed to send message: HTTP ${response.code()}")
                SendResult.Failed("HTTP ${response.code()}: ${response.message()}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to send message via relay")
            SendResult.Failed(e.message ?: "Unknown error")
        }
    }
    
    fun receiveMessages(): Flow<EncryptedMessage> = flow {
        while (coroutineContext.isActive) {
            try {
                if (NetworkUtils.isInternetAvailable(context)) {
                    val response = relayApi.getQueuedMessages(userId)
                    
                    if (response.isSuccessful) {
                        val messages = response.body()?.messages ?: emptyList()
                        
                        messages.forEach { dto ->
                            val message = EncryptedMessage(
                                id = dto.messageId,
                                senderId = dto.senderId,
                                recipientId = userId,
                                ciphertext = Base64.decode(dto.encryptedPayload, Base64.NO_WRAP),
                                timestamp = dto.timestamp
                            )
                            
                            Timber.d("Received message: ${message.id}")
                            emit(message)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to fetch queued messages")
            }
            
            delay(10000) // Poll every 10 seconds
        }
    }
    
    fun isAvailable(): Boolean {
        return NetworkUtils.isInternetAvailable(context)
    }
}
```

---

## Use Cases

### SendMessageUseCase.kt (Updated)

```kotlin
package com.meshcipher.domain.usecase

import com.meshcipher.data.encryption.SignalProtocolManager
import com.meshcipher.data.transport.EncryptedMessage
import com.meshcipher.data.transport.InternetTransport
import com.meshcipher.data.transport.SendResult
import com.meshcipher.domain.model.Message
import com.meshcipher.domain.model.MessageStatus
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.repository.MessageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val contactRepository: ContactRepository,
    private val signalProtocolManager: SignalProtocolManager,
    private val internetTransport: InternetTransport
) {
    suspend operator fun invoke(
        conversationId: String,
        recipientId: String,
        content: String
    ): Result<Message> = withContext(Dispatchers.IO) {
        try {
            // Get recipient
            val recipient = contactRepository.getContact(recipientId)
                ?: return@withContext Result.failure(Exception("Contact not found"))
            
            // Create message
            val message = Message(
                conversationId = conversationId,
                senderId = "me",
                recipientId = recipientId,
                content = content,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.PENDING,
                isOwnMessage = true
            )
            
            // Save to database
            messageRepository.insertMessage(message)
            
            // Encrypt
            val ciphertext = signalProtocolManager.encryptMessage(
                plaintext = content,
                recipientAddress = recipient.signalProtocolAddress
            )
            
            val encryptedMessage = EncryptedMessage(
                id = message.id,
                senderId = "me",
                recipientId = recipientId,
                ciphertext = ciphertext.serialize(),
                timestamp = message.timestamp
            )
            
            // Send via internet
            val sendResult = internetTransport.send(encryptedMessage, recipient)
            
            // Update status
            val status = when (sendResult) {
                is SendResult.Success -> MessageStatus.SENT
                is SendResult.Queued -> MessageStatus.SENT
                is SendResult.Failed -> MessageStatus.FAILED
            }
            
            messageRepository.updateMessageStatus(message.id, status)
            
            Timber.d("Message sent with status: $status")
            Result.success(message.copy(status = status))
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to send message")
            Result.failure(e)
        }
    }
}
```

### ReceiveMessageUseCase.kt

```kotlin
package com.meshcipher.domain.usecase

import com.meshcipher.data.encryption.SignalProtocolManager
import com.meshcipher.data.transport.EncryptedMessage
import com.meshcipher.domain.model.Message
import com.meshcipher.domain.model.MessageStatus
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.repository.ConversationRepository
import com.meshcipher.domain.repository.MessageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import timber.log.Timber
import javax.inject.Inject

class ReceiveMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val contactRepository: ContactRepository,
    private val conversationRepository: ConversationRepository,
    private val signalProtocolManager: SignalProtocolManager
) {
    suspend operator fun invoke(
        encryptedMessage: EncryptedMessage
    ): Result<Message> = withContext(Dispatchers.IO) {
        try {
            // Get sender
            val sender = contactRepository.getContact(encryptedMessage.senderId)
                ?: return@withContext Result.failure(Exception("Unknown sender: ${encryptedMessage.senderId}"))
            
            // Decrypt
            val ciphertext = try {
                when {
                    encryptedMessage.ciphertext.isNotEmpty() && 
                    encryptedMessage.ciphertext[0] == CiphertextMessage.PREKEY_TYPE.toByte() -> {
                        PreKeySignalMessage(encryptedMessage.ciphertext)
                    }
                    else -> {
                        SignalMessage(encryptedMessage.ciphertext)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse ciphertext")
                throw e
            }
            
            val plaintext = signalProtocolManager.decryptMessage(
                ciphertext = ciphertext,
                senderAddress = sender.signalProtocolAddress
            )
            
            // Find or create conversation
            val conversationId = conversationRepository.createOrGetConversation(sender.id)
            
            // Create message
            val message = Message(
                id = encryptedMessage.id,
                conversationId = conversationId,
                senderId = encryptedMessage.senderId,
                recipientId = "me",
                content = plaintext,
                timestamp = encryptedMessage.timestamp,
                status = MessageStatus.DELIVERED,
                isOwnMessage = false
            )
            
            // Save
            messageRepository.insertMessage(message)
            
            // Update conversation
            conversationRepository.incrementUnreadCount(conversationId)
            
            Timber.d("Received and decrypted message from ${sender.displayName}")
            Result.success(message)
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to receive message")
            Result.failure(e)
        }
    }
}
```

---

## Background Sync Worker

### MessageSyncWorker.kt

```kotlin
package com.meshcipher.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.meshcipher.data.transport.InternetTransport
import com.meshcipher.domain.usecase.ReceiveMessageUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.take
import timber.log.Timber

@HiltWorker
class MessageSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val internetTransport: InternetTransport,
    private val receiveMessageUseCase: ReceiveMessageUseCase
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            Timber.d("Starting message sync")
            
            internetTransport.receiveMessages()
                .take(20) // Limit to prevent infinite flow
                .catch { e ->
                    Timber.e(e, "Error receiving messages")
                }
                .collect { encryptedMessage ->
                    receiveMessageUseCase(encryptedMessage)
                }
            
            Timber.d("Message sync complete")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Message sync failed")
            Result.retry()
        }
    }
}
```

### Schedule Worker in Application

```kotlin
// In MeshCipherApplication.kt
override fun onCreate() {
    super.onCreate()
    
    // Initialize Timber
    if (BuildConfig.DEBUG) {
        Timber.plant(Timber.DebugTree())
    }
    
    // Schedule message sync
    scheduleMessageSync()
}

private fun scheduleMessageSync() {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    
    val syncRequest = PeriodicWorkRequestBuilder<MessageSyncWorker>(
        15, TimeUnit.MINUTES
    )
        .setConstraints(constraints)
        .build()
    
    WorkManager.getInstance(this).enqueueUniquePeriodicWork(
        "message_sync",
        ExistingPeriodicWorkPolicy.KEEP,
        syncRequest
    )
}
```

---

## Update ChatViewModel

```kotlin
// In ChatViewModel.kt

private val _sendingState = MutableStateFlow<SendingState>(SendingState.Idle)
val sendingState = _sendingState.asStateFlow()

sealed class SendingState {
    object Idle : SendingState()
    object Sending : SendingState()
    object Sent : SendingState()
    data class Error(val message: String) : SendingState()
}

fun sendMessage() {
    val content = _messageInput.value.trim()
    if (content.isEmpty()) return
    
    val contactValue = contact.value ?: return
    
    viewModelScope.launch {
        _sendingState.value = SendingState.Sending
        
        val result = sendMessageUseCase(
            conversationId = conversationId,
            recipientId = contactValue.id,
            content = content
        )
        
        result.fold(
            onSuccess = {
                _messageInput.value = ""
                _sendingState.value = SendingState.Sent
                delay(1000)
                _sendingState.value = SendingState.Idle
            },
            onFailure = { error ->
                _sendingState.value = SendingState.Error(error.message ?: "Failed")
                delay(3000)
                _sendingState.value = SendingState.Idle
            }
        )
    }
}
```

---

## Network Module

### NetworkModule.kt

```kotlin
package com.meshcipher.di

import android.content.Context
import com.meshcipher.data.remote.api.RelayApiService
import com.meshcipher.data.transport.InternetTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    
    // TODO: Replace with your ThinkPad's IP address or domain
    private const val BASE_URL = "http://YOUR_THINKPAD_IP:5000/"
    
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
            )
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    
    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    @Provides
    @Singleton
    fun provideRelayApiService(retrofit: Retrofit): RelayApiService {
        return retrofit.create(RelayApiService::class.java)
    }
    
    @Provides
    @Singleton
    fun provideInternetTransport(
        @ApplicationContext context: Context,
        relayApi: RelayApiService
    ): InternetTransport {
        return InternetTransport(context, relayApi)
    }
}
```

---

## Utilities

### NetworkUtils.kt

```kotlin
package com.meshcipher.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

object NetworkUtils {
    
    fun isInternetAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) 
            as? ConnectivityManager ?: return false
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) 
                ?: return false
            
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo?.isConnectedOrConnecting == true
        }
    }
}
```

---

## Testing

### InternetTransportTest.kt

```kotlin
package com.meshcipher.data.transport

import android.content.Context
import com.meshcipher.data.remote.api.RelayApiService
import com.meshcipher.data.remote.dto.RelayMessageResponse
import com.meshcipher.domain.model.Contact
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.signal.libsignal.protocol.SignalProtocolAddress
import retrofit2.Response

class InternetTransportTest {
    
    private lateinit var context: Context
    private lateinit var relayApi: RelayApiService
    private lateinit var transport: InternetTransport
    
    @Before
    fun setup() {
        context = mockk(relaxed = true)
        relayApi = mockk()
        transport = InternetTransport(context, relayApi)
    }
    
    @Test
    fun `send returns Queued on successful API call`() = runTest {
        // Given
        val message = EncryptedMessage(
            id = "msg-1",
            senderId = "user-1",
            recipientId = "user-2",
            ciphertext = byteArrayOf(1, 2, 3),
            timestamp = System.currentTimeMillis()
        )
        
        val contact = Contact(
            id = "user-2",
            displayName = "Test",
            publicKey = byteArrayOf(),
            identityKey = byteArrayOf(),
            signalProtocolAddress = SignalProtocolAddress("user-2", 1),
            lastSeen = System.currentTimeMillis()
        )
        
        coEvery { relayApi.relayMessage(any()) } returns Response.success(
            RelayMessageResponse(
                status = "queued",
                messageId = "msg-1",
                queuedAt = System.currentTimeMillis()
            )
        )
        
        // When
        val result = transport.send(message, contact)
        
        // Then
        assertTrue(result is SendResult.Queued)
    }
}
```

---

## Phase 3 Checklist

- [ ] Retrofit dependencies added
- [ ] Relay server code written
- [ ] PostgreSQL installed on ThinkPad
- [ ] Relay server deployed and running
- [ ] Relay server accessible from phone
- [ ] API models created
- [ ] RelayApiService implemented
- [ ] InternetTransport implemented
- [ ] SendMessageUseCase updated
- [ ] ReceiveMessageUseCase created
- [ ] MessageSyncWorker created
- [ ] WorkManager configured
- [ ] ChatViewModel updated
- [ ] NetworkModule created
- [ ] NetworkUtils created
- [ ] Can send messages (actually transmitted!)
- [ ] Can receive messages
- [ ] Messages encrypted end-to-end
- [ ] Background sync working
- [ ] Tests passing

---

## Next Phase

**Phase 4: Bluetooth Mesh** - See `Phase_4_Bluetooth_Mesh.md`