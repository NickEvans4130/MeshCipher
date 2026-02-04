# MeshCipher - Phase 1: Foundation
## Weeks 1-4: Project Setup, Database, Basic UI, Signal Protocol

---

## Phase 1 Overview

**Goals:**
- Complete project setup with dependencies
- Implement Room database with encryption
- Create base UI structure with navigation
- Integrate Signal Protocol for E2E encryption
- Basic contact and conversation management

**Deliverables:**
- App launches with navigation working
- Can create and view contacts
- Messages stored in encrypted database
- Signal Protocol sessions can be established
- Basic Compose UI screens functional

---

## Step 1: Project Setup

### Create New Android Project

**Settings:**
- Name: MeshCipher
- Package: com.meshcipher
- Language: Kotlin
- Minimum SDK: API 26 (Android 8.0)
- Build configuration: Kotlin DSL

### File Structure

```
app/
├── src/
│   ├── main/
│   │   ├── java/com/meshcipher/
│   │   │   ├── MeshCipherApplication.kt
│   │   │   ├── presentation/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── navigation/
│   │   │   │   │   └── Navigation.kt
│   │   │   │   ├── conversations/
│   │   │   │   │   ├── ConversationsScreen.kt
│   │   │   │   │   └── ConversationsViewModel.kt
│   │   │   │   ├── chat/
│   │   │   │   │   ├── ChatScreen.kt
│   │   │   │   │   └── ChatViewModel.kt
│   │   │   │   ├── contacts/
│   │   │   │   │   ├── ContactsScreen.kt
│   │   │   │   │   └── ContactsViewModel.kt
│   │   │   │   └── theme/
│   │   │   │       ├── Color.kt
│   │   │   │       ├── Theme.kt
│   │   │   │       └── Type.kt
│   │   │   ├── domain/
│   │   │   │   ├── model/
│   │   │   │   │   ├── Message.kt
│   │   │   │   │   ├── Contact.kt
│   │   │   │   │   └── Conversation.kt
│   │   │   │   ├── repository/
│   │   │   │   │   ├── MessageRepository.kt
│   │   │   │   │   ├── ContactRepository.kt
│   │   │   │   │   └── ConversationRepository.kt
│   │   │   │   └── usecase/
│   │   │   │       ├── GetContactsUseCase.kt
│   │   │   │       ├── GetConversationsUseCase.kt
│   │   │   │       └── GetMessagesUseCase.kt
│   │   │   ├── data/
│   │   │   │   ├── local/
│   │   │   │   │   ├── database/
│   │   │   │   │   │   ├── MeshCipherDatabase.kt
│   │   │   │   │   │   ├── MessageDao.kt
│   │   │   │   │   │   ├── ContactDao.kt
│   │   │   │   │   │   └── ConversationDao.kt
│   │   │   │   │   ├── entity/
│   │   │   │   │   │   ├── MessageEntity.kt
│   │   │   │   │   │   ├── ContactEntity.kt
│   │   │   │   │   │   └── ConversationEntity.kt
│   │   │   │   │   └── preferences/
│   │   │   │   │       └── AppPreferences.kt
│   │   │   │   ├── encryption/
│   │   │   │   │   ├── SignalProtocolManager.kt
│   │   │   │   │   ├── SignalProtocolStoreImpl.kt
│   │   │   │   │   └── KeyHelper.kt
│   │   │   │   └── repository/
│   │   │   │       ├── MessageRepositoryImpl.kt
│   │   │   │       ├── ContactRepositoryImpl.kt
│   │   │   │       └── ConversationRepositoryImpl.kt
│   │   │   ├── di/
│   │   │   │   ├── AppModule.kt
│   │   │   │   └── DatabaseModule.kt
│   │   │   └── util/
│   │   │       ├── Constants.kt
│   │   │       └── Extensions.kt
│   │   └── res/
│   │       ├── values/
│   │       │   ├── strings.xml
│   │       │   ├── colors.xml
│   │       │   └── themes.xml
│   │       └── drawable/
│   └── test/
│       └── java/com/meshcipher/
└── build.gradle.kts
```

---

## Step 2: Dependencies Configuration

### build.gradle.kts (Project level)

```kotlin
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("com.google.dagger.hilt.android") version "2.48" apply false
    id("com.google.devtools.ksp") version "1.9.20-1.0.14" apply false
}
```

### build.gradle.kts (App level)

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    kotlin("kapt")
}

android {
    namespace = "com.meshcipher"
    compileSdk = 34
    
    defaultConfig {
        applicationId = "com.meshcipher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Room schema export
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }
    
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        compose = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")
    
    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.5")
    
    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    
    // SQLCipher for encryption
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    
    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-compiler:2.48")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    
    // Signal Protocol
    implementation("org.signal:libsignal-android:0.44.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // Timber for logging
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("io.mockk:mockk:1.13.8")
    
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

---

## Step 3: Application Class

### MeshCipherApplication.kt

```kotlin
package com.meshcipher

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class MeshCipherApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        Timber.d("MeshCipher application started")
    }
}
```

---

## Step 4: Domain Models

### Message.kt

```kotlin
package com.meshcipher.domain.model

import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus,
    val isOwnMessage: Boolean = false
)

enum class MessageStatus {
    PENDING,
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}
```

### Contact.kt

```kotlin
package com.meshcipher.domain.model

import org.signal.libsignal.protocol.SignalProtocolAddress

data class Contact(
    val id: String,
    val displayName: String,
    val publicKey: ByteArray,
    val identityKey: ByteArray,
    val signalProtocolAddress: SignalProtocolAddress,
    val lastSeen: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as Contact
        
        return id == other.id
    }
    
    override fun hashCode(): Int {
        return id.hashCode()
    }
}
```

### Conversation.kt

```kotlin
package com.meshcipher.domain.model

data class Conversation(
    val id: String,
    val contactId: String,
    val contactName: String,
    val lastMessage: String?,
    val lastMessageTime: Long?,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false
)
```

---

## Step 5: Database Entities

### MessageEntity.kt

```kotlin
package com.meshcipher.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversation_id"), Index("timestamp")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "sender_id") val senderId: String,
    @ColumnInfo(name = "recipient_id") val recipientId: String,
    @ColumnInfo(name = "encrypted_content") val encryptedContent: ByteArray,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "status") val status: String
)
```

### ContactEntity.kt

```kotlin
package com.meshcipher.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contacts",
    indices = [Index("display_name")]
)
data class ContactEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "public_key") val publicKey: ByteArray,
    @ColumnInfo(name = "identity_key") val identityKey: ByteArray,
    @ColumnInfo(name = "signal_protocol_address") val signalProtocolAddress: String,
    @ColumnInfo(name = "last_seen") val lastSeen: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
```

### ConversationEntity.kt

```kotlin
package com.meshcipher.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [Index("last_message_timestamp", orders = [Index.Order.DESC])]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "last_message_id") val lastMessageId: String?,
    @ColumnInfo(name = "last_message_timestamp") val lastMessageTimestamp: Long?,
    @ColumnInfo(name = "unread_count") val unreadCount: Int,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean
)
```

---

## Step 6: Database DAOs

### MessageDao.kt

```kotlin
package com.meshcipher.data.local.database

import androidx.room.*
import com.meshcipher.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp DESC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>
    
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(conversationId: String, limit: Int): List<MessageEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)
    
    @Update
    suspend fun updateMessage(message: MessageEntity)
    
    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)
    
    @Delete
    suspend fun deleteMessage(message: MessageEntity)
    
    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteAllMessagesInConversation(conversationId: String)
}
```

### ContactDao.kt

```kotlin
package com.meshcipher.data.local.database

import androidx.room.*
import com.meshcipher.data.local.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    
    @Query("SELECT * FROM contacts ORDER BY display_name ASC")
    fun getAllContacts(): Flow<List<ContactEntity>>
    
    @Query("SELECT * FROM contacts WHERE id = :contactId")
    suspend fun getContact(contactId: String): ContactEntity?
    
    @Query("SELECT * FROM contacts WHERE id = :contactId")
    fun getContactFlow(contactId: String): Flow<ContactEntity?>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)
    
    @Update
    suspend fun updateContact(contact: ContactEntity)
    
    @Delete
    suspend fun deleteContact(contact: ContactEntity)
    
    @Query("SELECT COUNT(*) FROM contacts")
    suspend fun getContactCount(): Int
}
```

### ConversationDao.kt

```kotlin
package com.meshcipher.data.local.database

import androidx.room.*
import com.meshcipher.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    
    @Query("SELECT * FROM conversations ORDER BY is_pinned DESC, last_message_timestamp DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>
    
    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversation(conversationId: String): ConversationEntity?
    
    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    fun getConversationFlow(conversationId: String): Flow<ConversationEntity?>
    
    @Query("SELECT * FROM conversations WHERE contact_id = :contactId LIMIT 1")
    suspend fun getConversationByContactId(contactId: String): ConversationEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)
    
    @Update
    suspend fun updateConversation(conversation: ConversationEntity)
    
    @Query("UPDATE conversations SET unread_count = 0 WHERE id = :conversationId")
    suspend fun markConversationAsRead(conversationId: String)
    
    @Query("UPDATE conversations SET unread_count = unread_count + 1 WHERE id = :conversationId")
    suspend fun incrementUnreadCount(conversationId: String)
    
    @Query("UPDATE conversations SET is_pinned = NOT is_pinned WHERE id = :conversationId")
    suspend fun togglePin(conversationId: String)
    
    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)
}
```

---

## Step 7: Database Setup

### Type Converters

```kotlin
package com.meshcipher.data.local.database

import android.util.Base64
import androidx.room.TypeConverter

class Converters {
    
    @TypeConverter
    fun fromByteArray(value: ByteArray): String {
        return Base64.encodeToString(value, Base64.NO_WRAP)
    }
    
    @TypeConverter
    fun toByteArray(value: String): ByteArray {
        return Base64.decode(value, Base64.NO_WRAP)
    }
}
```

### MeshCipherDatabase.kt

```kotlin
package com.meshcipher.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.meshcipher.data.local.entity.ContactEntity
import com.meshcipher.data.local.entity.ConversationEntity
import com.meshcipher.data.local.entity.MessageEntity

@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        ConversationEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MeshCipherDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
}
```

---

## Step 8: Signal Protocol Implementation

### SignalProtocolStoreImpl.kt

```kotlin
package com.meshcipher.data.encryption

import android.content.Context
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.state.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignalProtocolStoreImpl @Inject constructor(
    private val context: Context
) : SignalProtocolStore {
    
    private val identityKeyStore = InMemoryIdentityKeyStore()
    private val preKeyStore = InMemoryPreKeyStore()
    private val signedPreKeyStore = InMemorySignedPreKeyStore()
    private val sessionStore = InMemorySessionStore()
    
    override fun getIdentityKeyPair(): IdentityKeyPair {
        return identityKeyStore.identityKeyPair
    }
    
    override fun getLocalRegistrationId(): Int {
        return identityKeyStore.localRegistrationId
    }
    
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        return identityKeyStore.saveIdentity(address, identityKey)
    }
    
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        return identityKeyStore.isTrustedIdentity(address, identityKey, direction)
    }
    
    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        return identityKeyStore.getIdentity(address)
    }
    
    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        return preKeyStore.loadPreKey(preKeyId)
    }
    
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        preKeyStore.storePreKey(preKeyId, record)
    }
    
    override fun containsPreKey(preKeyId: Int): Boolean {
        return preKeyStore.containsPreKey(preKeyId)
    }
    
    override fun removePreKey(preKeyId: Int) {
        preKeyStore.removePreKey(preKeyId)
    }
    
    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        return signedPreKeyStore.loadSignedPreKey(signedPreKeyId)
    }
    
    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        return signedPreKeyStore.loadSignedPreKeys()
    }
    
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        signedPreKeyStore.storeSignedPreKey(signedPreKeyId, record)
    }
    
    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        return signedPreKeyStore.containsSignedPreKey(signedPreKeyId)
    }
    
    override fun removeSignedPreKey(signedPreKeyId: Int) {
        signedPreKeyStore.removeSignedPreKey(signedPreKeyId)
    }
    
    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        return sessionStore.loadSession(address)
    }
    
    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> {
        return sessionStore.loadExistingSessions(addresses)
    }
    
    override fun getSubDeviceSessions(name: String): MutableList<Int> {
        return sessionStore.getSubDeviceSessions(name)
    }
    
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        sessionStore.storeSession(address, record)
    }
    
    override fun containsSession(address: SignalProtocolAddress): Boolean {
        return sessionStore.containsSession(address)
    }
    
    override fun deleteSession(address: SignalProtocolAddress) {
        sessionStore.deleteSession(address)
    }
    
    override fun deleteAllSessions(name: String) {
        sessionStore.deleteAllSessions(name)
    }
}
```

### SignalProtocolManager.kt

```kotlin
package com.meshcipher.data.encryption

import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignalProtocolManager @Inject constructor(
    private val signalProtocolStore: SignalProtocolStoreImpl
) {
    
    fun encryptMessage(
        plaintext: String,
        recipientAddress: SignalProtocolAddress
    ): CiphertextMessage {
        val sessionCipher = SessionCipher(signalProtocolStore, recipientAddress)
        return sessionCipher.encrypt(plaintext.toByteArray())
    }
    
    fun decryptMessage(
        ciphertext: CiphertextMessage,
        senderAddress: SignalProtocolAddress
    ): String {
        val sessionCipher = SessionCipher(signalProtocolStore, senderAddress)
        
        val plaintext = when (ciphertext.type) {
            CiphertextMessage.PREKEY_TYPE -> {
                sessionCipher.decrypt(PreKeySignalMessage(ciphertext.serialize()))
            }
            CiphertextMessage.WHISPER_TYPE -> {
                sessionCipher.decrypt(SignalMessage(ciphertext.serialize()))
            }
            else -> throw IllegalArgumentException("Unknown ciphertext type: ${ciphertext.type}")
        }
        
        return String(plaintext)
    }
    
    fun createSession(
        contact: com.meshcipher.domain.model.Contact,
        preKeyBundle: PreKeyBundle
    ) {
        val sessionBuilder = SessionBuilder(
            signalProtocolStore,
            contact.signalProtocolAddress
        )
        sessionBuilder.process(preKeyBundle)
    }
    
    fun getSafetyNumber(contact: com.meshcipher.domain.model.Contact): String {
        val identityKey = signalProtocolStore.getIdentity(contact.signalProtocolAddress)
        val myIdentityKey = signalProtocolStore.identityKeyPair
        
        if (identityKey == null) {
            return "No identity key found"
        }
        
        // Generate 60-digit safety number
        return generateSafetyNumber(myIdentityKey.publicKey, identityKey)
    }
    
    private fun generateSafetyNumber(
        localKey: IdentityKey,
        remoteKey: IdentityKey
    ): String {
        // Simplified safety number generation
        val combined = localKey.serialize() + remoteKey.serialize()
        val hash = combined.contentHashCode()
        return hash.toString().take(12).padEnd(12, '0')
    }
}
```

---

## Step 9: Repositories

### MessageRepositoryImpl.kt

```kotlin
package com.meshcipher.data.repository

import com.meshcipher.data.local.database.MessageDao
import com.meshcipher.data.local.entity.MessageEntity
import com.meshcipher.domain.model.Message
import com.meshcipher.domain.model.MessageStatus
import com.meshcipher.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao
) : MessageRepository {
    
    override fun getMessagesForConversation(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesForConversation(conversationId)
            .map { entities -> entities.map { it.toDomain() } }
    }
    
    override suspend fun insertMessage(message: Message) {
        messageDao.insertMessage(message.toEntity())
    }
    
    override suspend fun updateMessage(message: Message) {
        messageDao.updateMessage(message.toEntity())
    }
    
    override suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        messageDao.updateMessageStatus(messageId, status.name)
    }
    
    override suspend fun deleteMessage(message: Message) {
        messageDao.deleteMessage(message.toEntity())
    }
    
    private fun MessageEntity.toDomain(): Message {
        return Message(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            recipientId = recipientId,
            content = String(encryptedContent), // Temporarily store as plain text
            timestamp = timestamp,
            status = MessageStatus.valueOf(status),
            isOwnMessage = senderId == "me" // TODO: Get actual user ID
        )
    }
    
    private fun Message.toEntity(): MessageEntity {
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            recipientId = recipientId,
            encryptedContent = content.toByteArray(),
            timestamp = timestamp,
            status = status.name
        )
    }
}
```

### ContactRepositoryImpl.kt

```kotlin
package com.meshcipher.data.repository

import com.meshcipher.data.local.database.ContactDao
import com.meshcipher.data.local.entity.ContactEntity
import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.repository.ContactRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.signal.libsignal.protocol.SignalProtocolAddress
import javax.inject.Inject

class ContactRepositoryImpl @Inject constructor(
    private val contactDao: ContactDao
) : ContactRepository {
    
    override fun getAllContacts(): Flow<List<Contact>> {
        return contactDao.getAllContacts()
            .map { entities -> entities.map { it.toDomain() } }
    }
    
    override suspend fun getContact(contactId: String): Contact? {
        return contactDao.getContact(contactId)?.toDomain()
    }
    
    override fun getContactFlow(contactId: String): Flow<Contact?> {
        return contactDao.getContactFlow(contactId)
            .map { it?.toDomain() }
    }
    
    override suspend fun insertContact(contact: Contact) {
        contactDao.insertContact(contact.toEntity())
    }
    
    override suspend fun updateContact(contact: Contact) {
        contactDao.updateContact(contact.toEntity())
    }
    
    override suspend fun deleteContact(contact: Contact) {
        contactDao.deleteContact(contact.toEntity())
    }
    
    private fun ContactEntity.toDomain(): Contact {
        return Contact(
            id = id,
            displayName = displayName,
            publicKey = publicKey,
            identityKey = identityKey,
            signalProtocolAddress = SignalProtocolAddress(
                signalProtocolAddress,
                1
            ),
            lastSeen = lastSeen
        )
    }
    
    private fun Contact.toEntity(): ContactEntity {
        return ContactEntity(
            id = id,
            displayName = displayName,
            publicKey = publicKey,
            identityKey = identityKey,
            signalProtocolAddress = signalProtocolAddress.name,
            lastSeen = lastSeen
        )
    }
}
```

### ConversationRepositoryImpl.kt

```kotlin
package com.meshcipher.data.repository

import com.meshcipher.data.local.database.ConversationDao
import com.meshcipher.data.local.database.ContactDao
import com.meshcipher.data.local.database.MessageDao
import com.meshcipher.data.local.entity.ConversationEntity
import com.meshcipher.domain.model.Conversation
import com.meshcipher.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID
import javax.inject.Inject

class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao
) : ConversationRepository {
    
    override fun getAllConversations(): Flow<List<Conversation>> {
        return combine(
            conversationDao.getAllConversations(),
            contactDao.getAllContacts()
        ) { conversations, contacts ->
            conversations.mapNotNull { conv ->
                val contact = contacts.find { it.id == conv.contactId }
                contact?.let {
                    Conversation(
                        id = conv.id,
                        contactId = conv.contactId,
                        contactName = contact.displayName,
                        lastMessage = conv.lastMessageId?.let { "Last message" }, // TODO: Fetch actual message
                        lastMessageTime = conv.lastMessageTimestamp,
                        unreadCount = conv.unreadCount,
                        isPinned = conv.isPinned
                    )
                }
            }
        }
    }
    
    override suspend fun getConversation(conversationId: String): Conversation? {
        val entity = conversationDao.getConversation(conversationId) ?: return null
        val contact = contactDao.getContact(entity.contactId) ?: return null
        
        return Conversation(
            id = entity.id,
            contactId = entity.contactId,
            contactName = contact.displayName,
            lastMessage = null, // TODO: Fetch
            lastMessageTime = entity.lastMessageTimestamp,
            unreadCount = entity.unreadCount,
            isPinned = entity.isPinned
        )
    }
    
    override fun getConversationFlow(conversationId: String): Flow<Conversation?> {
        return conversationDao.getConversationFlow(conversationId)
            .combine(contactDao.getAllContacts()) { conv, contacts ->
                conv?.let { entity ->
                    val contact = contacts.find { it.id == entity.contactId }
                    contact?.let {
                        Conversation(
                            id = entity.id,
                            contactId = entity.contactId,
                            contactName = contact.displayName,
                            lastMessage = null,
                            lastMessageTime = entity.lastMessageTimestamp,
                            unreadCount = entity.unreadCount,
                            isPinned = entity.isPinned
                        )
                    }
                }
            }
    }
    
    override suspend fun createOrGetConversation(contactId: String): String {
        val existing = conversationDao.getConversationByContactId(contactId)
        if (existing != null) {
            return existing.id
        }
        
        val conversationId = UUID.randomUUID().toString()
        val conversation = ConversationEntity(
            id = conversationId,
            contactId = contactId,
            lastMessageId = null,
            lastMessageTimestamp = null,
            unreadCount = 0,
            isPinned = false
        )
        
        conversationDao.insertConversation(conversation)
        return conversationId
    }
    
    override suspend fun markConversationAsRead(conversationId: String) {
        conversationDao.markConversationAsRead(conversationId)
    }
    
    override suspend fun incrementUnreadCount(conversationId: String) {
        conversationDao.incrementUnreadCount(conversationId)
    }
    
    override suspend fun togglePin(conversationId: String) {
        conversationDao.togglePin(conversationId)
    }
    
    override suspend fun deleteConversation(conversationId: String) {
        val conversation = conversationDao.getConversation(conversationId)
        conversation?.let {
            conversationDao.deleteConversation(it)
            messageDao.deleteAllMessagesInConversation(conversationId)
        }
    }
}
```

---

## Step 10: Hilt Modules

### DatabaseModule.kt

```kotlin
package com.meshcipher.di

import android.content.Context
import androidx.room.Room
import com.meshcipher.data.local.database.ContactDao
import com.meshcipher.data.local.database.ConversationDao
import com.meshcipher.data.local.database.MeshCipherDatabase
import com.meshcipher.data.local.database.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): MeshCipherDatabase {
        // TODO: Use proper key derivation in production
        val passphrase = SQLiteDatabase.getBytes("meshcipher_secret_key".toCharArray())
        val factory = SupportFactory(passphrase)
        
        return Room.databaseBuilder(
            context,
            MeshCipherDatabase::class.java,
            "meshcipher.db"
        )
            .openHelperFactory(factory)
            .build()
    }
    
    @Provides
    fun provideMessageDao(database: MeshCipherDatabase): MessageDao {
        return database.messageDao()
    }
    
    @Provides
    fun provideContactDao(database: MeshCipherDatabase): ContactDao {
        return database.contactDao()
    }
    
    @Provides
    fun provideConversationDao(database: MeshCipherDatabase): ConversationDao {
        return database.conversationDao()
    }
}
```

### AppModule.kt

```kotlin
package com.meshcipher.di

import com.meshcipher.data.encryption.SignalProtocolManager
import com.meshcipher.data.encryption.SignalProtocolStoreImpl
import com.meshcipher.data.repository.ContactRepositoryImpl
import com.meshcipher.data.repository.ConversationRepositoryImpl
import com.meshcipher.data.repository.MessageRepositoryImpl
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.repository.ConversationRepository
import com.meshcipher.domain.repository.MessageRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    
    @Binds
    @Singleton
    abstract fun bindMessageRepository(
        impl: MessageRepositoryImpl
    ): MessageRepository
    
    @Binds
    @Singleton
    abstract fun bindContactRepository(
        impl: ContactRepositoryImpl
    ): ContactRepository
    
    @Binds
    @Singleton
    abstract fun bindConversationRepository(
        impl: ConversationRepositoryImpl
    ): ConversationRepository
}
```

---

## Step 11: Repository Interfaces

### MessageRepository.kt

```kotlin
package com.meshcipher.domain.repository

import com.meshcipher.domain.model.Message
import com.meshcipher.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getMessagesForConversation(conversationId: String): Flow<List<Message>>
    suspend fun insertMessage(message: Message)
    suspend fun updateMessage(message: Message)
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)
    suspend fun deleteMessage(message: Message)
}
```

### ContactRepository.kt

```kotlin
package com.meshcipher.domain.repository

import com.meshcipher.domain.model.Contact
import kotlinx.coroutines.flow.Flow

interface ContactRepository {
    fun getAllContacts(): Flow<List<Contact>>
    suspend fun getContact(contactId: String): Contact?
    fun getContactFlow(contactId: String): Flow<Contact?>
    suspend fun insertContact(contact: Contact)
    suspend fun updateContact(contact: Contact)
    suspend fun deleteContact(contact: Contact)
}
```

### ConversationRepository.kt

```kotlin
package com.meshcipher.domain.repository

import com.meshcipher.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun getAllConversations(): Flow<List<Conversation>>
    suspend fun getConversation(conversationId: String): Conversation?
    fun getConversationFlow(conversationId: String): Flow<Conversation?>
    suspend fun createOrGetConversation(contactId: String): String
    suspend fun markConversationAsRead(conversationId: String)
    suspend fun incrementUnreadCount(conversationId: String)
    suspend fun togglePin(conversationId: String)
    suspend fun deleteConversation(conversationId: String)
}
```

---

## Step 12: Use Cases

### GetContactsUseCase.kt

```kotlin
package com.meshcipher.domain.usecase

import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.repository.ContactRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetContactsUseCase @Inject constructor(
    private val contactRepository: ContactRepository
) {
    operator fun invoke(): Flow<List<Contact>> {
        return contactRepository.getAllContacts()
    }
}
```

### GetConversationsUseCase.kt

```kotlin
package com.meshcipher.domain.usecase

import com.meshcipher.domain.model.Conversation
import com.meshcipher.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetConversationsUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository
) {
    operator fun invoke(): Flow<List<Conversation>> {
        return conversationRepository.getAllConversations()
    }
}
```

### GetMessagesUseCase.kt

```kotlin
package com.meshcipher.domain.usecase

import com.meshcipher.domain.model.Message
import com.meshcipher.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetMessagesUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    operator fun invoke(conversationId: String): Flow<List<Message>> {
        return messageRepository.getMessagesForConversation(conversationId)
    }
}
```

---

## Testing

### MessageRepositoryTest.kt

```kotlin
package com.meshcipher.data.repository

import app.cash.turbine.test
import com.meshcipher.data.local.database.MessageDao
import com.meshcipher.data.local.entity.MessageEntity
import com.meshcipher.domain.model.Message
import com.meshcipher.domain.model.MessageStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MessageRepositoryTest {
    
    private lateinit var messageDao: MessageDao
    private lateinit var repository: MessageRepositoryImpl
    
    @Before
    fun setup() {
        messageDao = mockk(relaxed = true)
        repository = MessageRepositoryImpl(messageDao)
    }
    
    @Test
    fun `getMessagesForConversation returns mapped messages`() = runTest {
        // Given
        val conversationId = "conv-1"
        val messageEntities = listOf(
            MessageEntity(
                id = "msg-1",
                conversationId = conversationId,
                senderId = "user-1",
                recipientId = "user-2",
                encryptedContent = "Hello".toByteArray(),
                timestamp = 1000L,
                status = "SENT"
            )
        )
        
        every { messageDao.getMessagesForConversation(conversationId) } returns flowOf(messageEntities)
        
        // When
        repository.getMessagesForConversation(conversationId).test {
            val messages = awaitItem()
            
            // Then
            assertEquals(1, messages.size)
            assertEquals("msg-1", messages[0].id)
            assertEquals("Hello", messages[0].content)
            assertEquals(MessageStatus.SENT, messages[0].status)
            awaitComplete()
        }
    }
    
    @Test
    fun `insertMessage inserts entity`() = runTest {
        // Given
        val message = Message(
            id = "msg-1",
            conversationId = "conv-1",
            senderId = "user-1",
            recipientId = "user-2",
            content = "Hello",
            timestamp = 1000L,
            status = MessageStatus.PENDING
        )
        
        // When
        repository.insertMessage(message)
        
        // Then
        coVerify { messageDao.insertMessage(any()) }
    }
}
```

---

## AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".MeshCipherApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.MeshCipher"
        tools:targetApi="31">
        
        <activity
            android:name=".presentation.MainActivity"
            android:exported="true"
            android:theme="@style/Theme.MeshCipher"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

---

## Phase 1 Checklist

- [ ] Project created with correct settings
- [ ] Dependencies added to build.gradle
- [ ] Application class created with Hilt
- [ ] Domain models defined
- [ ] Database entities created
- [ ] DAOs implemented
- [ ] Database class with Room created
- [ ] Type converters added
- [ ] Signal Protocol store implemented
- [ ] Signal Protocol manager created
- [ ] Repository interfaces defined
- [ ] Repository implementations created
- [ ] Hilt modules configured
- [ ] Use cases created
- [ ] Basic tests written
- [ ] AndroidManifest configured

---

## Next Phase

**Phase 2: UI & Navigation** - See `Phase_2_UI_Navigation.md`

This will cover:
- Jetpack Compose UI components
- Navigation setup
- ViewModels
- Screen implementations
- Theme and styling