# MeshCipher

A privacy-focused encrypted messaging app for Android with multi-transport capabilities including Bluetooth mesh networking for offline communication.

## Features

- **End-to-End Encryption** - All messages encrypted using Signal Protocol
- **Bluetooth Mesh Networking** - Send messages without internet via multi-hop relay
- **Hardware-Bound Identity** - Cryptographic keys stored in Android Keystore
- **TOR Integration** - Optional metadata privacy through Orbot
- **Offline Support** - Messages queue and sync when connectivity returns
- **QR Code Contact Exchange** - Easy secure contact sharing

## Architecture

MeshCipher follows Clean Architecture with four layers:

```
┌─────────────────────────────────────────────────────────┐
│                    Presentation                          │
│         (Jetpack Compose UI, ViewModels)                │
├─────────────────────────────────────────────────────────┤
│                      Domain                              │
│      (Use Cases, Repository Interfaces, Models)         │
├─────────────────────────────────────────────────────────┤
│                       Data                               │
│  (Room + SQLCipher, Signal Protocol, Bluetooth, API)    │
├─────────────────────────────────────────────────────────┤
│                        DI                                │
│              (Hilt Dependency Injection)                │
└─────────────────────────────────────────────────────────┘
```

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose with Material Design 3
- **Database**: Room with SQLCipher encryption
- **Encryption**: Signal Protocol (libsignal-android)
- **DI**: Hilt
- **Async**: Kotlin Coroutines + Flow
- **Networking**: Retrofit + OkHttp
- **Logging**: Timber

---

## How It Works

### Identity System

MeshCipher creates a hardware-bound cryptographic identity on first launch:

1. **Key Generation**: Ed25519 keypair generated in Android Keystore (hardware-backed on supported devices)
2. **User ID**: Derived from Base64-encoded public key, creating a unique identifier
3. **Biometric Protection**: Signing operations require biometric authentication
4. **Non-Exportable**: Private keys never leave the secure hardware

```
┌──────────────────┐
│  Android Keystore │
│  ┌──────────────┐ │
│  │ Private Key  │ │  ← Never exported
│  │  (Ed25519)   │ │
│  └──────────────┘ │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│   Public Key     │ → Base64 encode → User ID
└──────────────────┘
```

### Message Encryption

All messages are encrypted end-to-end using Signal Protocol:

1. **Session Establishment**: When you add a contact, a Signal session is created using their public key
2. **Message Encryption**: Each message is encrypted with a unique message key (Double Ratchet)
3. **Forward Secrecy**: Compromising one key doesn't compromise past or future messages
4. **Storage**: Messages stored encrypted in SQLCipher database

```
Sender                                    Recipient
┌─────────┐                              ┌─────────┐
│Plaintext│                              │Plaintext│
└────┬────┘                              └────▲────┘
     │                                        │
     ▼                                        │
┌─────────┐    ┌─────────────────┐      ┌─────────┐
│ Encrypt │───▶│  Encrypted Msg  │─────▶│ Decrypt │
│ (Signal)│    │  (via transport)│      │ (Signal)│
└─────────┘    └─────────────────┘      └─────────┘
```

### Transport Layer

MeshCipher supports three connection modes:

#### 1. Direct Internet (Default)
- Messages sent to relay server over HTTPS
- Server queues messages for offline recipients
- Fastest delivery, server sees metadata (not content)

#### 2. TOR Relay
- Messages routed through TOR network via Orbot
- Server cannot see your IP address
- 3-5 second latency, enhanced privacy

#### 3. P2P Only (Bluetooth Mesh)
- No internet required
- Messages hop between nearby devices
- Maximum privacy, fully decentralized

```
┌─────────────────────────────────────────────────────────┐
│                   Transport Manager                      │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐ │
│  │   Internet  │  │  TOR Relay  │  │ Bluetooth Mesh  │ │
│  │  Transport  │  │  Transport  │  │   Transport     │ │
│  └──────┬──────┘  └──────┬──────┘  └───────┬─────────┘ │
│         │                │                  │           │
│         ▼                ▼                  ▼           │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐ │
│  │Relay Server │  │ TOR Network │  │  Nearby Peers   │ │
│  └─────────────┘  └─────────────┘  └─────────────────┘ │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### Bluetooth Mesh Networking

The mesh network enables offline messaging through multi-hop relay:

#### Discovery
1. **Advertising**: Device broadcasts its presence via BLE (Bluetooth Low Energy)
2. **Scanning**: Device listens for other MeshCipher users
3. **Peer Tracking**: Discovered peers stored with RSSI (signal strength) and timestamps
4. **Stale Removal**: Peers not seen for 30 seconds are removed

#### Message Routing
```
Device A ──────▶ Device B ──────▶ Device C ──────▶ Device D
(Sender)        (Relay)          (Relay)         (Recipient)
   │               │                │                │
   └── Hop 1 ──────┴─── Hop 2 ─────┴──── Hop 3 ────┘
```

1. **Send**: Message created with destination user ID and TTL (Time To Live = 5)
2. **Relay**: Each hop decrements TTL and adds device to path
3. **Delivery**: When destination device receives message, it's delivered locally
4. **Loop Prevention**: Path tracking prevents relaying to already-visited devices

#### GATT Protocol
Messages are transferred using Bluetooth GATT (Generic Attribute Profile):

- **Service UUID**: Custom MeshCipher service
- **Message Characteristic**: Write-only, receives encrypted messages
- **ACK Characteristic**: Read/notify for delivery confirmation

```
┌────────────────────────────────────────────────────────┐
│                    GATT Server                          │
│  ┌────────────────────────────────────────────────┐   │
│  │           MeshCipher Service                    │   │
│  │  ┌──────────────────┐  ┌──────────────────┐   │   │
│  │  │ Message Char     │  │ ACK Char         │   │   │
│  │  │ (Write)          │  │ (Read/Notify)    │   │   │
│  │  └──────────────────┘  └──────────────────┘   │   │
│  └────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────┘
```

#### Mesh Message Format
```
┌─────────┬──────────┬─────────┬───────────┬─────────────┬──────────┐
│ Msg ID  │ Origin   │ Dest    │ Timestamp │ TTL │ Path  │ Payload  │
│ (UUID)  │ User ID  │ User ID │  (Long)   │(Int)│(List) │ (Bytes)  │
└─────────┴──────────┴─────────┴───────────┴─────────────┴──────────┘
```

### Notifications & Unread Tracking

#### Push Notifications
- High-priority notification channel for incoming messages
- Shows sender name and message preview
- Tapping notification opens the conversation

#### Unread Count System
1. **Increment**: When message received, conversation's unread count increases
2. **Display**: Badge shown on contact avatar and conversation list
3. **Reset**: When user opens chat, count resets to zero
4. **Real-time**: Updates immediately when viewing chat and new messages arrive

### Contact Exchange

Contacts are exchanged via QR codes containing:

```
meshcipher://add?data=<base64-encoded-contact-card>

Contact Card Contents:
- User ID (public key based)
- Public encryption key
- Device ID
- Device name
- Verification code (6 digits)
```

#### Verification Code
Generated from both parties' public keys to prevent MITM attacks:
```
SHA-256(your_public_key + their_public_key) → first 6 digits
```

Both users should verify they see the same code.

### Data Storage

#### Database Schema
```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│    contacts     │     │  conversations  │     │    messages     │
├─────────────────┤     ├─────────────────┤     ├─────────────────┤
│ id (PK)         │◄───┐│ id (PK)         │◄───┐│ id (PK)         │
│ display_name    │    ││ contact_id (FK) │────┘│ conversation_id │───┐
│ public_key      │    │├─────────────────┤     │ sender_id       │   │
│ identity_key    │    ││ last_message_id │     │ recipient_id    │   │
│ signal_address  │    ││ last_timestamp  │     │ encrypted_content│  │
│ last_seen       │    ││ unread_count    │     │ timestamp       │   │
│ created_at      │    ││ is_pinned       │     │ status          │   │
└─────────────────┘    │└─────────────────┘     └─────────────────┘   │
                       │                                               │
                       └───────────────────────────────────────────────┘
```

#### Encryption at Rest
- SQLCipher encrypts entire database file
- Encryption key derived from device-specific secrets
- Database unreadable without proper key

---

## Building

### Prerequisites
- Android Studio Hedgehog or newer
- JDK 17 or 21
- Android SDK 34

### Debug Build
```bash
./gradlew assembleDebug
```

### Release Build
```bash
./gradlew assembleRelease
```

### Running Tests
```bash
./gradlew test
```

---

## Permissions

MeshCipher requires the following permissions:

| Permission | Purpose |
|------------|---------|
| `BLUETOOTH_SCAN` | Discover nearby mesh peers |
| `BLUETOOTH_ADVERTISE` | Broadcast presence to mesh |
| `BLUETOOTH_CONNECT` | Connect to peers for messaging |
| `CAMERA` | Scan QR codes for contact exchange |
| `INTERNET` | Relay server communication |
| `POST_NOTIFICATIONS` | Message notifications |
| `FOREGROUND_SERVICE` | Keep mesh network active |
| `USE_BIOMETRIC` | Protect cryptographic operations |

---

## Project Structure

```
app/src/main/java/com/meshcipher/
├── data/
│   ├── bluetooth/          # Mesh networking
│   │   ├── BluetoothMeshManager.kt    # BLE advertising/scanning
│   │   ├── BluetoothMeshService.kt    # Foreground service
│   │   ├── GattServerManager.kt       # GATT server for receiving
│   │   ├── GattClientManager.kt       # GATT client for sending
│   │   └── routing/
│   │       └── MeshRouter.kt          # Multi-hop routing logic
│   ├── identity/           # Cryptographic identity
│   ├── local/              # Room database & DAOs
│   ├── remote/             # Relay server API
│   ├── repository/         # Repository implementations
│   ├── signal/             # Signal Protocol integration
│   ├── tor/                # TOR/Orbot integration
│   └── transport/          # Transport layer abstraction
├── di/                     # Hilt modules
├── domain/
│   ├── model/              # Domain models
│   ├── repository/         # Repository interfaces
│   └── usecase/            # Business logic use cases
└── presentation/
    ├── chat/               # Chat screen
    ├── contacts/           # Contact list & details
    ├── conversations/      # Conversation list
    ├── mesh/               # Mesh network visualization
    ├── onboarding/         # First-time setup
    ├── settings/           # App settings
    └── theme/              # Material Design theme
```

---

## Security Considerations

### What MeshCipher Protects
- Message content (end-to-end encrypted)
- Contact list (encrypted database)
- Private keys (hardware-backed storage)

### What MeshCipher Does NOT Protect (without TOR)
- Metadata (who talks to whom, when)
- IP addresses visible to relay server
- Message timing patterns

### Recommendations
- Use P2P mode for maximum privacy
- Enable TOR for internet messaging when metadata privacy matters
- Verify contact fingerprints in person when possible
- Keep your device secure (screen lock, updated OS)

---

## License

[Add your license here]

---

## Contributing

[Add contribution guidelines here]
