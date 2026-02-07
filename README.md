# MeshCipher
![License](https://img.shields.io/github/license/NickEvans4130/MeshCipher)

A privacy-focused encrypted messaging app for Android with five independent transport layers including Bluetooth mesh, WiFi Direct, and Tor hidden services for serverless and anonymous communication.

## Features

- **End-to-End Encryption** - Signal Protocol (X3DH + Double Ratchet) for all messages
- **Five Transport Modes** - Direct relay, Tor relay, WiFi Direct, Bluetooth mesh, P2P Tor hidden services
- **Secure Media Sharing** - Images, video, and voice messages encrypted with AES-256-GCM
- **Hardware-Bound Identity** - Ed25519 keys stored in Android Keystore (TEE/StrongBox)
- **Offline Messaging** - Bluetooth mesh and WiFi Direct work without any internet
- **Anonymous Messaging** - P2P Tor mode uses .onion hidden services with no relay server
- **Disappearing Messages** - Configurable auto-delete (on close, 5min, 1hr, 24hr, 7d, 30d)
- **QR Code Contact Exchange** - In-person key verification
- **Encrypted Database** - SQLCipher (AES-256) for all local data

## Transport Modes

| Mode | Network | Range | Speed | Privacy | Server Required |
|------|---------|-------|-------|---------|-----------------|
| Direct | HTTPS | Global | Fast | IP visible to relay | Yes |
| Tor Relay | HTTPS via Tor | Global | Medium | IP hidden from relay | Yes (+ Orbot) |
| WiFi Direct | WiFi P2P | ~100m | Fast | No network trace | No |
| Bluetooth Mesh | BLE | ~30-100m/hop | Low | No network trace | No |
| P2P Tor | Tor hidden services | Global | Medium | Fully anonymous | No (+ embedded Tor) |

All transports deliver the same Signal Protocol-encrypted payload. The encryption layer is independent of the transport layer.

## Architecture

```
Presentation    Jetpack Compose + MVVM (ViewModel + StateFlow)
Domain          Pure Kotlin models, repository interfaces
Data            Room/SQLCipher, Signal Protocol, BLE, WiFi P2P, Tor, Retrofit
DI              Hilt
```

See [docs/](docs/) for detailed technical documentation.

## Building

### Prerequisites
- JDK 17 or 21
- Android SDK 34

### Commands

```bash
# Debug build
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew assembleDebug --no-daemon

# Unit tests
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest --no-daemon
```

## Permissions

| Permission | Purpose |
|------------|---------|
| `BLUETOOTH_SCAN` | Discover nearby mesh peers |
| `BLUETOOTH_ADVERTISE` | Broadcast presence to mesh |
| `BLUETOOTH_CONNECT` | Connect to peers for messaging |
| `NEARBY_WIFI_DEVICES` | WiFi Direct peer discovery |
| `ACCESS_FINE_LOCATION` | Required for WiFi P2P on older APIs |
| `CAMERA` | QR code scanning for contact exchange |
| `INTERNET` | Relay server and Tor communication |
| `POST_NOTIFICATIONS` | Message notifications |
| `FOREGROUND_SERVICE` | Keep mesh/Tor services active |

## Project Structure

```
com.meshcipher/
  data/
    bluetooth/      BLE mesh (advertising, scanning, GATT, routing)
    cleanup/        Disappearing messages lifecycle
    crypto/         Signal Protocol encrypt/decrypt
    identity/       Hardware key management (Android Keystore)
    local/          Room + SQLCipher database, DataStore preferences
    media/          AES-256-GCM media encryption, file storage
    remote/         Relay server API (Retrofit)
    security/       Message sequence tracking (replay prevention)
    tor/            Embedded Tor, hidden services, P2P connections
    transport/      TransportManager + all transport implementations
    wifidirect/     WiFi P2P manager, TCP socket protocol
  domain/
    model/          Message, Contact, Identity, MeshMessage, P2PMessage, etc.
    repository/     Repository interfaces
  presentation/
    chat/           Chat screen (bubbles, media, voice)
    components/     Tactical UI components (header, cards, FAB)
    contacts/       Contact list, detail, add
    conversations/  Conversation list
    guide/          Onboarding guide (connection mode walkthrough)
    mesh/           Bluetooth mesh status
    navigation/     NavHost, Screen routes
    onboarding/     Identity creation
    p2ptor/         P2P Tor status
    settings/       App settings
    theme/          Dark tactical theme (Inter + Roboto Mono)
    wifidirect/     WiFi Direct status
```

## Documentation

Detailed technical documentation is in [docs/](docs/):

- [Architecture](docs/architecture.md) - Clean Architecture layers, data flow, DI
- [Tech Stack](docs/tech_stack.md) - All dependencies with versions
- [Cryptography](docs/cryptography.md) - Signal Protocol, AES-256-GCM, hardware identity
- [Transport Layer](docs/networking.md) - TransportManager fallback logic
- [WiFi Direct](docs/wifi_direct.md) - Discovery, socket protocol, chunked file transfer
- [P2P Tor](docs/p2p_tor.md) - Hidden services, SOCKS5, wire format
- [Bluetooth Mesh](docs/bluetooth_mesh.md) - BLE, GATT, mesh routing, flooding
- [Media Handling](docs/media_handling.md) - Encryption, MediaMessageEnvelope
- [Data Storage](docs/data_storage.md) - Schema, entities, DAOs, migrations
- [Privacy Features](docs/privacy.md) - Disappearing messages, sequence tracking

## Security

See [SECURITY.md](SECURITY.md) for our vulnerability disclosure policy.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
