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
                                                              -> VerifySafetyNumber{contactId}
                                  -> AddContact{?userId,?onionAddress,?publicKey,?deviceId}
                      -> Settings -> MeshNetwork
                                  -> ShareContact
                                  -> ScanContact -> AddContact
                                  -> WifiDirect
                                  -> P2PTor
                                  -> Guide
                                  -> LinkedDevices -> ScanDeviceQr -> DeviceLinkApproval{requestJson}
```

## Domain Layer (`domain/`)

Pure Kotlin with no Android framework dependencies. Contains:

### Models (Shared KMM — `shared/commonMain`)

These live in the `:shared` KMM module and are consumed by both Android and desktop:

- `Message` - id, conversationId, senderId, recipientId, content, status, timestamp, mediaAttachment
- `Contact` - id, displayName, publicKey, identityKey, signalProtocolAddress, onionAddress, currentSafetyNumber, verifiedSafetyNumber, safetyNumberVerifiedAt, safetyNumberChangedAt
- `MediaAttachment` - decrypted media reference with local file path and media type
- `ConnectionMode` - enum: DIRECT, TOR_RELAY, P2P_ONLY, P2P_TOR
- `LinkedDevice` - deviceId, deviceName, deviceType, publicKeyHex, linkedAt, approved

### Models (Android-only)

- `Conversation` - id, contactId, lastMessage, unreadCount, isPinned, messageExpiryMode
- `Identity` - userId (SHA-256 hash of public key), hardwarePublicKey, deviceId, deviceName
- `MeshPeer` - deviceId, userId, rssi, lastSeen, isInRange
- `MeshMessage` - binary mesh protocol message with TTL and hop tracking
- `P2PMessage` - JSON-over-TCP message for Tor hidden service communication
- `WifiDirectMessage` - sealed class hierarchy for WiFi Direct protocol (Text, FileTransfer, FileChunk, Acknowledgment)
- `MediaMessageEnvelope` - encrypted media container with AES key/IV and metadata
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
| `crypto/` | Signal Protocol session management, encrypt/decrypt, SafetyNumberManager |
| `encryption/` | Low-level AES-256-GCM helpers |
| `identity/` | Hardware-backed key generation (Android Keystore), identity persistence |
| `local/` | Room database (SQLCipher), entities, DAOs, DataStore preferences |
| `media/` | AES-256-GCM encryption (transport + at-rest), file storage, EXIF stripping |
| `network/` | NetworkMonitor (ConnectivityManager → StateFlow<Boolean>) |
| `queue/` | OfflineQueueManager (in-memory queue, retry on reconnect) |
| `recovery/` | Session recovery helpers |
| `relay/` | WebSocketManager (real-time push delivery via OkHttp WebSocket) |
| `remote/` | Retrofit API service for relay server |
| `repository/` | Concrete repository implementations |
| `security/` | Message sequence tracking (replay attack prevention) |
| `service/` | MessageForwardingService (forwards messages to approved linked devices) |
| `tor/` | Orbot integration, embedded Tor daemon, hidden service server, P2P client |
| `transport/` | TransportManager, SmartModeManager, and all transport implementations |
| `wifidirect/` | WiFi P2P manager, TCP socket manager |
| `worker/` | WorkManager tasks for message sync and scheduled cleanup |

## Dependency Injection (`di/`)

Hilt modules provide:

- **Singletons**: Database, DAOs, Repositories, TransportManager, IdentityManager, SignalProtocolManager, AppPreferences, TorManager, BluetoothMeshManager, WifiDirectManager, P2PConnectionManager, MediaEncryptor, MediaFileManager, WebSocketManager, MessageCleanupManager, MessageSequenceTracker, SafetyNumberManager, LinkedDevicesRepository, MessageForwardingService, SmartModeManager, NetworkMonitor, OfflineQueueManager
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

## Data Flow: Receiving a Message (WebSocket)

```
1. WebSocketManager receives {"type":"new_message",...} from server
2. Parses QueuedMessage from JSON payload
3. ReceiveMessageUseCase.processAndAcknowledge(queuedMessage)
   -> Decrypts with SignalProtocolManager
   -> Saves to Room database
   -> Acknowledges receipt to server
4. UI updates automatically via Room Flow<List<Message>>
```

If WebSocket is disconnected, messages are received via HTTP polling (3s in chat, 15s on conversations list, 15min background WorkManager).

## Data Flow: Receiving Media

```
1. Transport receives payload with contentType=1
2. Payload parsed as MediaMessageEnvelope JSON
3. MediaEncryptor.decrypt(envelope.encryptedData, envelope.encryptionKey, envelope.encryptionIv)
4. MediaFileManager.saveMedia(mediaId, decryptedBytes, mediaType)
   -> Encrypts with per-file AES-256-GCM key
   -> Writes ciphertext to media_encrypted/{type}/{mediaId}
   -> Stores per-file key in EncryptedSharedPreferences
5. Message entity created with MediaAttachment referencing encrypted file path
6. ChatScreen decrypts media on demand for display:
   -> Images: decryptMedia() -> ByteArray -> BitmapFactory.decodeByteArray()
   -> Video/Voice: decryptMediaToTempFile() -> temporary File for playback
```

## Foreground Services

| Service | Notification ID | Purpose |
|---------|----------------|---------|
| BluetoothMeshService | 1001 | BLE advertising, scanning, GATT server, message relay |
| P2PTorService | 1002 | Tor daemon, hidden service server |

Both services are `START_STICKY` and run as foreground services with persistent notifications.

## KMM Shared Module (`:shared`)

The `:shared` Kotlin Multiplatform module provides code shared between Android and desktop:

- **Targets**: `androidTarget` + `jvm("desktop")` (iOS targets present but inactive on Linux)
- **commonMain**: Domain models (`Contact`, `Message`, `MediaAttachment`, `ConnectionMode`, `LinkedDevice`), `SafetyNumberGenerator` (open class), `KeyManager` (expect class), `sha512` / `generateUUID` (expect functions)
- **androidMain actuals**: SHA-512 via `MessageDigest`, UUID via `java.util.UUID`, `KeyManager` via Android Keystore; `ContactExtensions.kt` (Signal Protocol address helper)
- **desktopMain actuals**: SHA-512 via `MessageDigest`, UUID via `java.util.UUID`, `KeyManager` via file-based AES-256-GCM under `~/.config/meshcipher/`
- Android domain model files are type aliases to shared types — no import changes across the Android codebase

## Smart Mode

Smart Mode (`SmartModeManager`) automatically selects the best available transport at runtime:

- Tracks the `ActiveTransport` as a `StateFlow` exposed to `ChatViewModel` via `activeTransportLabel`
- `reportTransportUsed()` updates the active transport after each successful send
- `NetworkMonitor` observes `ConnectivityManager` callbacks and exposes `StateFlow<Boolean>`
- `OfflineQueueManager` holds an in-memory queue and emits a `retryTrigger` when connectivity is restored
- Controlled by `AppPreferences.smartModeEnabled` (default `true`) and `AppPreferences.preferTor` (default `false`)

## Device Linking (Android ↔ Desktop)

The linking flow connects the Android app to the desktop client:

1. Desktop generates a `DeviceLinkRequest` encoded as `meshcipher://link/<base64url-JSON>` and displays it as a QR code (`DeviceLinkManager` via ZXing)
2. Android scans the QR via `QRScannerScreen` (CameraX + ML Kit) and decodes the URI
3. `DeviceLinkApprovalScreen` presents device details; the user approves or rejects
4. On approval, `LinkedDevice` is upserted into the `linked_devices` table with `approved = true`
5. `MessageForwardingService` monitors the database and forwards incoming messages to all approved linked devices via `InternetTransport.sendMessage()`
