# Privacy Features

## Disappearing Messages

MeshCipher supports automatic message deletion with configurable expiry modes.

### MessageExpiryMode

```kotlin
enum class MessageExpiryMode(val displayName: String, val durationMs: Long) {
    NEVER("Never", 0),
    ON_APP_CLOSE("On App Close", -1),
    FIVE_MINUTES("5 Minutes", 300_000),
    ONE_HOUR("1 Hour", 3_600_000),
    TWENTY_FOUR_HOURS("24 Hours", 86_400_000),
    SEVEN_DAYS("7 Days", 604_800_000),
    THIRTY_DAYS("30 Days", 2_592_000_000)
}
```

### Configuration Levels

1. **Global default**: Set in `AppPreferences.messageExpiryMode`. Applies to all conversations that don't have a per-conversation override.
2. **Per-conversation**: Set in `ConversationEntity.messageExpiryMode`. Overrides the global default for that specific conversation.

### MessageCleanupManager

Implements `DefaultLifecycleObserver` and is registered with `ProcessLifecycleOwner` to detect app lifecycle transitions.

#### ON_APP_CLOSE Cleanup

Triggered on `onStop()` (app moves to background):

```
1. Query all conversations with explicit messageExpiryMode set
2. For each where mode == ON_APP_CLOSE:
   -> messageDao.deleteAllMessagesInConversation(conversationId)

3. Read global messageExpiryMode from AppPreferences
4. If global == ON_APP_CLOSE:
   -> Query conversations with messageExpiryMode == null (using global default)
   -> Delete all messages in those conversations
```

#### Time-Based Cleanup

For time-based modes (5min, 1hr, 24hr, 7d, 30d):

```
cutoffTimestamp = System.currentTimeMillis() - expiryMode.durationMs
messageDao.deleteExpiredMessages(conversationId, cutoffTimestamp)
```

This deletes all messages older than the configured duration.

### Media Cleanup

When messages with media attachments are deleted (either by expiry or manual deletion), the associated media files under `context.filesDir/media_encrypted/` are also cleaned up. `MediaFileManager.deleteMedia(mediaId, mediaType)` removes both the encrypted file from disk and the corresponding per-file encryption key and IV from EncryptedSharedPreferences.

## EXIF Metadata Stripping

All outgoing images have identifying EXIF metadata stripped before encryption and sending. This prevents metadata-based identification of the sender, their device, or their location.

Stripped metadata includes:
- **GPS**: Coordinates, altitude, speed, destination, timestamps, processing method
- **Timestamps**: Original datetime, digitized datetime, timezone offsets
- **Device info**: Camera make/model, software version, body and lens serial numbers, camera owner name
- **Author**: Artist, copyright, user comments, image description
- **Thumbnails**: Thumbnail dimensions (thumbnails can embed their own EXIF with location data)

EXIF orientation is read and applied to the bitmap before stripping, ensuring photos display correctly despite the orientation tag being removed.

## Encrypted Media at Rest

Media files are never stored as plaintext on the filesystem. Each file is encrypted with a unique AES-256-GCM key before being written to disk. Per-file keys are stored in EncryptedSharedPreferences, backed by the Android Keystore hardware master key. Files are decrypted on demand: images to in-memory byte arrays, video/voice to temporary cache files for playback. See [Media Handling](media_handling.md) for full details.

## Message Sequence Tracking

`MessageSequenceTracker` prevents replay attacks by maintaining per-sender sequence counters.

### Implementation

```kotlin
class MessageSequenceTracker @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("message_sequences", Context.MODE_PRIVATE)

    fun validateAndUpdate(senderId: String, sequenceNumber: Long): Boolean {
        val lastSeen = prefs.getLong(senderId, -1)
        if (sequenceNumber <= lastSeen) return false  // Replay detected
        prefs.edit().putLong(senderId, sequenceNumber).apply()
        return true
    }
}
```

### Threat Model

- Prevents an attacker from re-submitting previously captured encrypted messages
- Each sender has an independent counter
- Sequence numbers must be strictly increasing
- Backed by SharedPreferences (persists across app restarts)

## Contact Verification

### QR Code Exchange

Contacts are added by scanning QR codes containing a `ContactCard`:

```kotlin
data class ContactCard(
    val userId: String,
    val publicKey: String,       // Base64-encoded Ed25519 public key
    val identityKey: String,     // Base64-encoded Signal identity key
    val signalProtocolAddress: String,
    val verificationCode: String,
    val onionAddress: String?    // Optional .onion address
)
```

QR scanning verifies physical presence - both users must be in the same location to exchange codes.

### Safety Numbers

After establishing a Signal Protocol session, users can verify their connection by comparing safety numbers displayed in the Contact Detail screen. These are derived from both parties' identity keys:

```
safetyNumber = SHA-256(localIdentityKey + remoteIdentityKey)
              -> formatted as 12-digit numeric string
```

## Data Sovereignty

### No Cloud Backup

- Database encrypted with SQLCipher (opaque without key)
- Identity keys stored in hardware-backed Android Keystore (non-exportable)
- No cloud sync, no server-side message history
- All data lives exclusively on the device

### Account Deletion

Deleting the identity:
1. Removes hardware key from Android Keystore
2. Clears IdentityStorage (EncryptedSharedPreferences)
3. Database and media files can be wiped via `cleanupAllMedia()`

### Transport Metadata

| Transport | What the network sees | What remains after delivery |
|-----------|-----------------------|---------------------------|
| Direct Relay | Sender ID, recipient ID, encrypted blob, timestamp, sender IP | Server queue (cleared after ACK) |
| Tor Relay | Same as Direct but sender IP hidden (Tor exit node visible) | Server queue (cleared after ACK) |
| WiFi Direct | Nothing (device-to-device, no network infrastructure) | Nothing |
| Bluetooth Mesh | BLE advertisement hashes (not message content) | Nothing |
| P2P Tor | Nothing (both endpoints hidden behind .onion) | Nothing |
