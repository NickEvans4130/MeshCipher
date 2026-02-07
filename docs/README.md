# MeshCipher Technical Documentation

Comprehensive technical reference for **MeshCipher**, a privacy-focused encrypted messaging application for Android with multi-transport message delivery.

## Table of Contents

1. [Architecture](architecture.md) - Clean Architecture layers, data flow, dependency injection
2. [Tech Stack](tech_stack.md) - Languages, frameworks, libraries, and build configuration
3. [Cryptography](cryptography.md) - Identity, Signal Protocol, encryption at rest, media encryption
4. [Transport Layer](networking.md) - Transport manager, fallback logic, all five transport modes
5. [WiFi Direct](wifi_direct.md) - P2P discovery, socket protocol, chunked file transfer
6. [P2P Tor](p2p_tor.md) - Embedded Tor, hidden services, SOCKS5 proxying, wire format
7. [Bluetooth Mesh](bluetooth_mesh.md) - BLE advertising, GATT server, mesh routing, flooding algorithm
8. [Media Handling](media_handling.md) - AES-256-GCM encryption, MediaMessageEnvelope, cross-transport delivery
9. [Data Storage](data_storage.md) - Room + SQLCipher schema, entities, DAOs, migrations
10. [Privacy Features](privacy.md) - Disappearing messages, message sequence tracking, cleanup lifecycle
11. [Self-Hosting](self_hosting.md) - Relay server setup, configuration, connecting clients

## System Overview

MeshCipher is an Android messaging application that provides end-to-end encrypted communication over five independent transport layers:

| Transport | Network | Range | Bandwidth | Anonymity |
|-----------|---------|-------|-----------|-----------|
| Direct Relay | Internet (HTTPS) | Global | High | IP visible to relay |
| Tor Relay | Internet via Tor | Global | Medium | IP hidden |
| WiFi Direct | WiFi P2P (802.11) | ~100m | High | No network trace |
| Bluetooth Mesh | BLE + GATT | ~30-100m per hop | Low | No network trace |
| P2P Tor | Internet via Tor hidden services | Global | Medium | Fully anonymous |

All transports deliver the same Signal Protocol-encrypted payload. The transport layer is transparent to the encryption layer - messages are encrypted before transport selection and decrypted after delivery regardless of the path taken.

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
# Compile
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:compileDebugKotlin --no-daemon

# Unit tests
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest --no-daemon
```

## Package Structure

```
com.meshcipher/
  data/
    auth/           # Token storage
    bluetooth/      # BLE mesh manager, GATT server, routing
    cleanup/        # Message expiry/cleanup
    crypto/         # Signal Protocol encryption
    identity/       # Hardware key management, identity storage
    local/          # Room database, entities, DAOs, preferences
    media/          # Media encryption, file management
    remote/         # Relay server API (Retrofit)
    security/       # Message sequence tracking
    settings/       # (unused - preferences in local/)
    tor/            # Orbot integration, embedded Tor, P2P Tor
    transport/      # Transport manager and all transport implementations
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
    mesh/           # Bluetooth mesh status screen
    navigation/     # NavHost and Screen routes
    onboarding/     # Identity creation
    p2ptor/         # P2P Tor status screen
    scan/           # QR code scanner (CameraX)
    settings/       # Settings screen
    share/          # QR code generation
    theme/          # Colors, typography, theme
    util/           # Avatar color utility
    wifidirect/     # WiFi Direct status screen
```
