# Data Storage

## Database

- **Engine**: SQLite via Room Persistence Library
- **Encryption**: SQLCipher (AES-256-CBC with HMAC-SHA256 page authentication)
- **Version**: 4
- **Key Storage**: Database encryption key stored in EncryptedSharedPreferences (backed by Android Keystore master key)

## Entities

### ContactEntity (`contacts`)

```kotlin
@Entity(tableName = "contacts", indices = [Index("display_name")])
data class ContactEntity(
    @PrimaryKey
    val id: String,                       // userId (SHA-256 hash of public key)
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "public_key")
    val publicKey: ByteArray,             // ECDSA P-256 public key
    @ColumnInfo(name = "identity_key")
    val identityKey: ByteArray,           // Signal Protocol identity key (Curve25519)
    @ColumnInfo(name = "signal_protocol_address")
    val signalProtocolAddress: String,    // "userId@deviceId"
    @ColumnInfo(name = "last_seen")
    val lastSeen: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "onion_address")
    val onionAddress: String? = null      // .onion address for P2P Tor (added in migration 3->4)
)
```

### ConversationEntity (`conversations`)

```kotlin
@Entity(
    tableName = "conversations",
    indices = [Index("last_message_timestamp", orders = [Order.DESC])]
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,                       // UUID
    @ColumnInfo(name = "contact_id")
    val contactId: String,
    @ColumnInfo(name = "last_message_id")
    val lastMessageId: String?,
    @ColumnInfo(name = "last_message_timestamp")
    val lastMessageTimestamp: Long?,
    @ColumnInfo(name = "unread_count")
    val unreadCount: Int,
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean,
    @ColumnInfo(name = "message_expiry_mode")
    val messageExpiryMode: String? = null // MessageExpiryMode.name (added in migration 1->3)
)
```

### MessageEntity (`messages`)

```kotlin
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversation_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("conversation_id"), Index("timestamp")]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,                        // UUID
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "sender_id")
    val senderId: String,
    @ColumnInfo(name = "recipient_id")
    val recipientId: String,
    @ColumnInfo(name = "encrypted_content")
    val encryptedContent: ByteArray,       // Signal Protocol ciphertext
    val timestamp: Long,
    val status: String,                    // SENDING, SENT, DELIVERED, READ, FAILED
    @ColumnInfo(name = "media_id")
    val mediaId: String? = null,           // UUID (added in migration 1->3)
    @ColumnInfo(name = "media_type")
    val mediaType: String? = null,         // IMAGE, VIDEO, VOICE (added in migration 1->3)
    @ColumnInfo(name = "media_metadata_json")
    val mediaMetadataJson: String? = null  // Serialized MediaAttachment (added in migration 1->3)
)
```

## DAOs

### ContactDao

| Method | Return | Query |
|--------|--------|-------|
| `getAllContacts()` | `Flow<List<ContactEntity>>` | SELECT * ORDER BY display_name ASC |
| `getContact(id)` | `suspend ContactEntity?` | SELECT * WHERE id = :id |
| `getContactFlow(id)` | `Flow<ContactEntity?>` | SELECT * WHERE id = :id |
| `insertContact(contact)` | `suspend Unit` | INSERT OR REPLACE |
| `updateContact(contact)` | `suspend Unit` | UPDATE |
| `deleteContact(id)` | `suspend Unit` | DELETE WHERE id = :id |
| `getContactCount()` | `suspend Int` | SELECT COUNT(*) |

### MessageDao

| Method | Return | Query |
|--------|--------|-------|
| `getMessagesForConversation(id)` | `Flow<List<MessageEntity>>` | SELECT * WHERE conversation_id = :id ORDER BY timestamp DESC |
| `getRecentMessages(id, limit)` | `suspend List<MessageEntity>` | SELECT * WHERE conversation_id = :id ORDER BY timestamp DESC LIMIT :limit |
| `insertMessage(message)` | `suspend Unit` | INSERT OR REPLACE |
| `updateMessage(message)` | `suspend Unit` | UPDATE |
| `updateMessageStatus(id, status)` | `suspend Unit` | UPDATE SET status = :status WHERE id = :id |
| `deleteMessage(id)` | `suspend Unit` | DELETE WHERE id = :id |
| `deleteAllMessagesInConversation(id)` | `suspend Unit` | DELETE WHERE conversation_id = :id |
| `deleteExpiredMessages(id, cutoff)` | `suspend Unit` | DELETE WHERE conversation_id = :id AND timestamp < :cutoff |

### ConversationDao

| Method | Return | Query |
|--------|--------|-------|
| `getAllConversations()` | `Flow<List<ConversationEntity>>` | SELECT * ORDER BY is_pinned DESC, last_message_timestamp DESC |
| `getConversation(id)` | `suspend ConversationEntity?` | SELECT * WHERE id = :id |
| `getConversationFlow(id)` | `Flow<ConversationEntity?>` | SELECT * WHERE id = :id |
| `getConversationByContactId(contactId)` | `suspend ConversationEntity?` | SELECT * WHERE contact_id = :contactId |
| `insertConversation(conversation)` | `suspend Unit` | INSERT OR REPLACE |
| `markConversationAsRead(id)` | `suspend Unit` | UPDATE SET unread_count = 0 WHERE id = :id |
| `incrementUnreadCount(id)` | `suspend Unit` | UPDATE SET unread_count = unread_count + 1 WHERE id = :id |
| `togglePin(id)` | `suspend Unit` | UPDATE SET is_pinned = NOT is_pinned WHERE id = :id |
| `setMessageExpiryMode(id, mode)` | `suspend Unit` | UPDATE SET message_expiry_mode = :mode WHERE id = :id |
| `getConversationsWithExpiryMode()` | `suspend List<ConversationEntity>` | SELECT * WHERE message_expiry_mode IS NOT NULL |

## Migrations

### Version 1 -> 3

```sql
ALTER TABLE messages ADD COLUMN media_id TEXT DEFAULT NULL;
ALTER TABLE messages ADD COLUMN media_type TEXT DEFAULT NULL;
ALTER TABLE messages ADD COLUMN media_metadata_json TEXT DEFAULT NULL;
ALTER TABLE conversations ADD COLUMN message_expiry_mode TEXT DEFAULT NULL;
```

Added media attachment support and per-conversation message expiry.

### Version 2 -> 3

```sql
ALTER TABLE conversations ADD COLUMN message_expiry_mode TEXT DEFAULT NULL;
```

For databases that started at version 2.

### Version 3 -> 4

```sql
ALTER TABLE contacts ADD COLUMN onion_address TEXT DEFAULT NULL;
```

Added .onion address field for P2P Tor hidden service addressing.

## Preferences (DataStore)

`AppPreferences` uses Jetpack DataStore for non-sensitive key-value preferences:

| Key | Type | Default | Purpose |
|-----|------|---------|---------|
| `user_id` | String? | null | Current user's ID |
| `display_name` | String? | null | User's display name |
| `onboarding_complete` | Boolean | false | Whether onboarding finished |
| `connection_mode` | String | "DIRECT" | Active ConnectionMode |
| `mesh_enabled` | Boolean | false | Bluetooth mesh toggle |
| `message_expiry_mode` | String | "NEVER" | Global default expiry |
| `onion_address` | String? | null | This device's .onion address |
| `has_seen_guide` | Boolean | false | Whether onboarding guide was viewed |

## Sensitive Storage

EncryptedSharedPreferences (backed by Android Keystore master key) stores:

| Store | Contents |
|-------|----------|
| `TokenStorage` (`auth_prefs`) | JWT authentication tokens |
| `IdentityStorage` | Identity metadata (userId, deviceId, public key) |
| `DatabaseKeyProvider` (`db_key_prefs`) | SQLCipher 32-byte encryption key (base64) |
| `SignalProtocolStoreImpl` (`signal_protocol_store`) | Identity key pair, registration ID, sessions, pre-keys, signed pre-keys, sender keys, Kyber pre-keys (all Base64-encoded serialized records) |
| `MediaFileManager` (`media_encryption_keys`) | Per-file AES-256 keys and IVs for at-rest media encryption |
| `EmbeddedTorManager` | ED25519-V3 hidden service private key |
| `MessageSequenceTracker` | Per-sender message sequence numbers |

## Foreign Key Constraints

- `messages.conversation_id` -> `conversations.id` (CASCADE on delete)
  - Deleting a conversation automatically deletes all its messages
- `conversations.contact_id` -> `contacts.id` (no explicit FK constraint in Room, managed at application level)
