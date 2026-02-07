# Architecture

MeshCipher follows Clean Architecture with MVVM in the presentation layer, enforced by Hilt dependency injection.

## Layer Diagram

```
+-------------------------------------------+
|  Presentation Layer                       |
|  Jetpack Compose + ViewModels             |
|  (MVVM, StateFlow, navigation)            |
+-------------------------------------------+
        |               |
        v               v
+-------------------------------------------+
|  Domain Layer                             |
|  Pure Kotlin models + repository          |
|  interfaces + use cases                   |
+-------------------------------------------+
        ^               ^
        |               |
+-------------------------------------------+
|  Data Layer                               |
|  Room, Retrofit, BLE, Tor, WiFi Direct,   |
|  Signal Protocol, SQLCipher               |
+-------------------------------------------+
        ^
        |
+-------------------------------------------+
|  Dependency Injection (Hilt)              |
|  Singleton, ViewModelScoped bindings      |
+-------------------------------------------+
```

## Presentation Layer (`presentation/`)

MVVM pattern using Jetpack Compose and `ViewModel`.

- **Screens**: Composable functions observing ViewModel state via `collectAsState()`
- **ViewModels**: Expose `StateFlow<T>` for UI state, accept events via public methods
- **Navigation**: Single `NavHost` in `MeshCipherNavigation` composable, routes defined in sealed `Screen` class
- **Theme**: Dark-only tactical theme (`TacticalMeshCipherTheme`), Inter + Roboto Mono typography

### Screen Lifecycle

```
MainActivity
  -> TacticalMeshCipherTheme
    -> hasIdentity check (IdentityManager)
      -> false: OnboardingScreen (identity creation)
        -> hasSeenGuide check (AppPreferences)
          -> false: GuideScreen (onboarding guide)
      -> true: MeshCipherNavigation (NavHost)
```

### Navigation Graph

```
Conversations (start) -> Chat{conversationId}
                      -> Contacts -> ContactDetail{contactId} -> Chat
                                  -> AddContact{?userId,?onionAddress}
                      -> Settings -> MeshNetwork
                                  -> ShareContact
                                  -> ScanContact -> AddContact
                                  -> WifiDirect
                                  -> P2PTor
                                  -> Guide
```

## Domain Layer (`domain/`)

Pure Kotlin with no Android framework dependencies. Contains:

### Models
- `Message` - id, conversationId, senderId, recipientId, content, status, timestamp, mediaAttachment
- `Contact` - id, displayName, publicKey, identityKey, signalProtocolAddress, onionAddress
- `Conversation` - id, contactId, lastMessage, unreadCount, isPinned, messageExpiryMode
- `Identity` - userId (SHA-256 hash of public key), hardwarePublicKey, deviceId, deviceName
- `ConnectionMode` - enum: DIRECT, TOR_RELAY, P2P_ONLY, P2P_TOR
- `MeshPeer` - deviceId, userId, rssi, lastSeen, isInRange
- `MeshMessage` - binary mesh protocol message with TTL and hop tracking
- `P2PMessage` - JSON-over-TCP message for Tor hidden service communication
- `WifiDirectMessage` - sealed class hierarchy for WiFi Direct protocol (Text, FileTransfer, FileChunk, Acknowledgment)
- `MediaMessageEnvelope` - encrypted media container with AES key/IV and metadata
- `MediaAttachment` - decrypted media reference with local file path
- `ContactCard` - QR code exchange format (userId, publicKey, onionAddress)
- `MessageExpiryMode` - enum: NEVER, ON_APP_CLOSE, 5min, 1hr, 24hr, 7d, 30d

### Repository Interfaces
- `MessageRepository` - CRUD for messages, conversation queries
- `ContactRepository` - CRUD for contacts
- `IdentityRepository` - identity creation and retrieval

## Data Layer (`data/`)

Implements domain interfaces and handles all I/O:

### Sub-packages

| Package | Responsibility |
|---------|---------------|
| `auth/` | JWT token storage (EncryptedSharedPreferences) |
| `bluetooth/` | BLE mesh manager, GATT server/client, mesh routing |
| `cleanup/` | Message expiry lifecycle (ProcessLifecycleOwserver) |
| `crypto/` | Signal Protocol session management, encrypt/decrypt |
| `identity/` | Hardware-backed key generation (Android Keystore), identity persistence |
| `local/` | Room database (SQLCipher), entities, DAOs, DataStore preferences |
| `media/` | AES-256-GCM encryption, file storage management |
| `remote/` | Retrofit API service for relay server |
| `security/` | Message sequence tracking (replay attack prevention) |
| `tor/` | Orbot integration, embedded Tor daemon, hidden service server, P2P client |
| `transport/` | TransportManager and per-mode transport implementations |
| `wifidirect/` | WiFi P2P manager, TCP socket manager |
| `worker/` | WorkManager tasks for message sync and scheduled cleanup |

## Dependency Injection (`di/`)

Hilt modules provide:

- **Singletons**: Database, DAOs, Repositories, TransportManager, IdentityManager, SignalProtocolManager, AppPreferences, TorManager, BluetoothMeshManager, WifiDirectManager, P2PConnectionManager, MediaEncryptor, MediaFileManager, MessageCleanupManager, MessageSequenceTracker
- **ViewModelScoped**: Use cases injected via `@HiltViewModel` constructor

## Data Flow: Sending a Message

```
1. ChatViewModel.sendMessage(text)
2. SignalProtocolManager.encryptMessage(text, recipientAddress)
   -> Returns CiphertextMessage (encrypted bytes)
3. TransportManager.sendWithFallback(recipientId, encryptedBytes, contentType)
   -> Reads current ConnectionMode from AppPreferences
   -> Tries transports in priority order per mode:
      DIRECT:    P2PTor? -> WiFiDirect? -> InternetTransport -> BluetoothMesh
      TOR_RELAY: P2PTor? -> WiFiDirect? -> InternetTransport(via Tor proxy) -> BluetoothMesh
      P2P_ONLY:  WiFiDirect -> BluetoothMesh
      P2P_TOR:   P2PTor -> WiFiDirect -> BluetoothMesh
4. Transport delivers encrypted bytes to recipient
5. Recipient's transport layer receives bytes
6. SignalProtocolManager.decryptMessage(bytes, senderAddress)
7. Message saved to Room database
8. UI updated via Flow<List<Message>>
```

## Data Flow: Receiving Media

```
1. Transport receives payload with contentType=1
2. Payload parsed as MediaMessageEnvelope JSON
3. MediaEncryptor.decrypt(envelope.encryptedData, envelope.encryptionKey, envelope.encryptionIv)
4. MediaFileManager.saveMedia(mediaId, decryptedBytes, mediaType)
5. Message entity created with MediaAttachment referencing local file path
6. ChatScreen observes messages Flow and renders media inline
```

## Foreground Services

| Service | Notification ID | Purpose |
|---------|----------------|---------|
| BluetoothMeshService | 1001 | BLE advertising, scanning, GATT server, message relay |
| P2PTorService | 1002 | Tor daemon, hidden service server |

Both services are `START_STICKY` and run as foreground services with persistent notifications.
