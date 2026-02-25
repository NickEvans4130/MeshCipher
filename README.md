# MeshCipher
![License](https://img.shields.io/github/license/NickEvans4130/MeshCipher)

A privacy-focused encrypted messaging app for Android with five independent transport layers including Bluetooth mesh, WiFi Direct, and Tor hidden services for serverless and anonymous communication.

## Features

- **End-to-End Encryption** - Signal Protocol (X3DH + Double Ratchet) for all messages
- **Five Transport Modes** - Direct relay, Tor relay, WiFi Direct, Bluetooth mesh, P2P Tor hidden services
- **Real-Time Messaging** - WebSocket push delivery with automatic fallback to polling
- **Secure Media Sharing** - Images, video, and voice messages encrypted with AES-256-GCM in transit and at rest
- **EXIF Metadata Stripping** - GPS, timestamps, device info automatically removed from all outgoing images
- **Hardware-Bound Identity** - Ed25519 keys stored in Android Keystore (TEE/StrongBox)
- **Offline Messaging** - Bluetooth mesh and WiFi Direct work without any internet
- **Anonymous Messaging** - P2P Tor mode uses .onion hidden services with no relay server
- **Disappearing Messages** - Configurable auto-delete (on close, 5min, 1hr, 24hr, 7d, 30d)
- **QR Code Contact Exchange** - In-person key verification
- **Encrypted Database** - SQLCipher (AES-256) for all local data
- **Persistent Encrypted Sessions** - Signal Protocol state survives app restarts, encrypted at rest

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
    media/          AES-256-GCM media encryption (transport + at-rest), EXIF stripping
    relay/          WebSocket real-time message delivery
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

## Desktop App

A Compose Desktop client for Linux (Fedora/Ubuntu) and Windows, sharing the same KMM encryption and identity layer as the Android app.

### Features

- Device linking via QR code (desktop shows QR, phone scans and approves)
- End-to-end encrypted messaging (ECDH-derived AES-256-GCM; Signal Protocol X3DH planned)
- SQLite message persistence via Exposed ORM
- Relay WebSocket transport with automatic reconnect (exponential backoff)
- TOR relay mode — routes WebSocket traffic via system `tor` daemon (SOCKS5 port 19050)
- P2P TOR mode — desktop runs a `.onion` hidden service for relay-free direct messaging
- OS keyring via libsecret (GNOME Keyring / KWallet); plaintext file fallback

### Quick start

```bash
# Configure relay (one-time)
mkdir -p ~/.config/meshcipher
cat > ~/.config/meshcipher/relay.conf <<EOF
relayUrl=https://relay.meshcipher.com
authToken=<your JWT token>
EOF

# Build and run
./desktopApp/run.sh
```

### First run

1. The app opens on the **Link** screen and shows a QR code
2. On your phone: Settings → Linked Devices → Link New Device → scan QR
3. Approve the link on the phone
4. Contacts appear in the conversations list — start messaging

### TOR mode

Install the `tor` daemon (Fedora/Ubuntu):
```bash
sudo dnf install tor   # Fedora
sudo apt install tor   # Ubuntu/Debian
```

Enable TOR mode in app settings. The desktop starts the `tor` process, waits for full bootstrap, then routes all relay traffic through SOCKS5 port 19050. The relay server sees a TOR exit node, not your real IP.

**P2P TOR**: the desktop can also create a `.onion` hidden service. Enable it in settings — the app appends `HiddenServiceDir` to `torrc`, sends SIGHUP to reload TOR, and displays the `.onion` address once generated. Share it with contacts for direct, relay-free, fully anonymous messaging.

### Packaging

```bash
# Fedora (.rpm)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :desktopApp:packageRpm --no-daemon

# Debian/Ubuntu (.deb)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :desktopApp:packageDeb --no-daemon
```

### Configuration files

All config under `~/.config/meshcipher/`:

| File | Contents |
|------|----------|
| `relay.conf` | `relayUrl` and `authToken` |
| `identity.pub` | EC P-256 public key (X.509 encoded) |
| `identity.key.enc` | AES-256-GCM encrypted private key |
| `wrap.key` | Wrap key fallback (deleted when libsecret is available) |
| `device.id` | Stable desktop device UUID |

### Testing

```bash
./desktopApp/test-messaging.sh
```

Verifies build, key storage, relay config, TOR availability, and interactively checks device linking and send/receive flows.

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
