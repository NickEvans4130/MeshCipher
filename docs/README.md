# MeshCipher Technical Documentation

Comprehensive technical reference for **MeshCipher**, a privacy-focused encrypted messaging application for Android with multi-transport message delivery and a Compose Desktop companion client.

## Table of Contents

1. [Architecture](architecture.md) - Clean Architecture layers, data flow, dependency injection, KMM shared module, Smart Mode, device linking
2. [Tech Stack](tech_stack.md) - Languages, frameworks, libraries, build configuration, KMM and desktop deps
3. [Cryptography](cryptography.md) - Identity, Signal Protocol, safety numbers, encryption at rest, media encryption
4. [Transport Layer](networking.md) - Transport manager, Smart Mode, WebSocket real-time delivery, fallback logic, all five transport modes
5. [WiFi Direct](wifi_direct.md) - P2P discovery, socket protocol, chunked file transfer
6. [P2P Tor](p2p_tor.md) - Embedded Tor, hidden services, SOCKS5 proxying, wire format
7. [Bluetooth Mesh](bluetooth_mesh.md) - BLE advertising, GATT server, mesh routing, flooding algorithm
8. [Media Handling](media_handling.md) - AES-256-GCM encryption (transport + at-rest), EXIF stripping, MediaMessageEnvelope, cross-transport delivery
9. [Data Storage](data_storage.md) - Room + SQLCipher schema (v6), entities, DAOs, migrations
10. [Privacy Features](privacy.md) - Disappearing messages, safety numbers, message sequence tracking, cleanup lifecycle
11. [Self-Hosting](self_hosting.md) - Relay server setup, configuration, connecting clients

## System Overview

MeshCipher is an Android messaging application (with a Compose Desktop companion) that provides end-to-end encrypted communication over five independent transport layers:

| Transport | Network | Range | Bandwidth | Anonymity |
|-----------|---------|-------|-----------|-----------|
| Direct Relay | Internet (HTTPS) | Global | High | IP visible to relay |
| Tor Relay | Internet via Tor | Global | Medium | IP hidden |
| WiFi Direct | WiFi P2P (802.11) | ~100m | High | No network trace |
| Bluetooth Mesh | BLE + GATT | ~30-100m per hop | Low | No network trace |
| P2P Tor | Internet via Tor hidden services | Global | Medium | Fully anonymous |

All transports deliver the same Signal Protocol-encrypted payload. The transport layer is transparent to the encryption layer — messages are encrypted before transport selection and decrypted after delivery regardless of the path taken.

**Smart Mode** automatically selects the best available transport at runtime. Users can still lock to a specific `ConnectionMode` by disabling Smart Mode in Settings.

## Build Configuration

- **Language**: Kotlin 1.9.24
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **JVM Target**: 17
- **AGP**: 8.5.2
- **KSP**: 1.9.24-1.0.20
- **Compose BOM**: 2023.10.01

### Build Commands

```bash
# Android: compile
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:compileDebugKotlin --no-daemon

# Android: unit tests
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest --no-daemon

# Shared KMM module
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :shared:compileDebugKotlinAndroid --no-daemon

# Desktop app
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :desktopApp:compileKotlin --no-daemon

# Desktop: run
./desktopApp/run.sh
```

## Package Structure

```
com.meshcipher/
  data/
    auth/           # JWT token storage
    bluetooth/      # BLE mesh manager, GATT server, routing
    cleanup/        # Message expiry/cleanup
    crypto/         # Signal Protocol encryption, SafetyNumberManager
    encryption/     # AES-256-GCM helpers
    identity/       # Hardware key management, identity storage
    local/          # Room database (v6), entities, DAOs, preferences
    media/          # Media encryption (transport + at-rest), EXIF stripping
    network/        # NetworkMonitor (connectivity state)
    queue/          # OfflineQueueManager
    recovery/       # Session recovery
    relay/          # WebSocket real-time message delivery
    remote/         # Relay server API (Retrofit)
    repository/     # Concrete repository implementations
    security/       # Message sequence tracking (replay prevention)
    service/        # MessageForwardingService (linked device forwarding)
    tor/            # Embedded Tor, P2P Tor
    transport/      # TransportManager, SmartModeManager, all transport implementations
    wifidirect/     # WiFi Direct manager, socket manager
    worker/         # WorkManager tasks (sync, cleanup)
  di/               # Hilt modules
  domain/
    model/          # Domain entities (Message, Contact, Identity, etc.)
    repository/     # Repository interfaces
  presentation/
    chat/           # Chat screen + ViewModel
    components/     # Reusable tactical UI components
    contacts/       # Contact management screens
    conversations/  # Conversation list screen
    guide/          # Onboarding guide
    linking/        # Linked devices, QR scanner, device approval
    mesh/           # Bluetooth mesh status screen
    navigation/     # NavHost and Screen routes
    onboarding/     # Identity creation
    p2ptor/         # P2P Tor status screen
    permissions/    # Runtime permission flow
    scan/           # QR code scanner (CameraX + ML Kit)
    settings/       # Settings screen
    share/          # QR code generation
    theme/          # Colors, typography, theme
    util/           # Avatar color utility
    verify/         # Safety number verification screen
    wifidirect/     # WiFi Direct status screen

shared/             # KMM module
  commonMain/       # Shared models, SafetyNumberGenerator, KeyManager (expect)
  androidMain/      # Android actuals (Keystore, MessageDigest, UUID)
  desktopMain/      # Desktop actuals (file-based keys, MessageDigest, UUID)
```
