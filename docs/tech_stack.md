# Tech Stack

## Core

| Component | Version | Purpose |
|-----------|---------|---------|
| Kotlin | 1.9.24 | Primary language |
| Android Gradle Plugin | 8.5.2 | Build system |
| KSP | 1.9.24-1.0.20 | Kotlin Symbol Processing (Hilt, Room codegen) |
| JVM Target | 17 | Bytecode target |
| Min SDK | 26 | Android 8.0 Oreo |
| Target SDK | 34 | Android 14 |

## UI

| Library | Purpose |
|---------|---------|
| Jetpack Compose (BOM 2023.10.01) | Declarative UI toolkit |
| Material Design 3 | Design system and components |
| Compose Navigation | Single-activity navigation with type-safe routes |
| CameraX | Camera preview for QR code scanning |
| ML Kit Barcode Scanning | On-device QR/barcode decoding (replaces ZXing in scanning path) |
| Hilt Navigation Compose | ViewModel injection in composables |

## Data & Persistence

| Library | Purpose |
|---------|---------|
| Room | SQLite ORM with Flow-based reactive queries |
| SQLCipher | AES-256 transparent database encryption |
| DataStore Preferences | Key-value preferences (connection mode, expiry settings) |
| EncryptedSharedPreferences | Sensitive values (tokens, Signal Protocol state, media encryption keys, Tor private keys) |

## Cryptography

| Library | Purpose |
|---------|---------|
| libsignal-client | Signal Protocol (X3DH key agreement, Double Ratchet) |
| Android Keystore | Hardware-backed Ed25519 key storage |
| javax.crypto (AES/GCM/NoPadding) | Media encryption (256-bit keys, 128-bit GCM tags) |
| java.security (SHA-256) | User ID derivation from public key |
| java.util.Base64 | Encoding (used instead of android.util.Base64 for testability) |

## Networking

| Library | Purpose |
|---------|---------|
| Retrofit 2 + OkHttp | REST API client for relay server |
| OkHttp WebSocket | Real-time message delivery (built-in WebSocket client) |
| OkHttp SOCKS Proxy | Tor relay mode (proxy through 127.0.0.1:9050) |
| Android WiFi P2P (WifiP2pManager) | WiFi Direct discovery and group formation |
| Android Bluetooth LE | BLE advertising, scanning, GATT server/client |
| Guardian Project tor-android (org.torproject.jni) | Embedded Tor daemon for P2P hidden services |
| net.freehaven.tor.control (jtorctl) | Tor control port commands (hidden service management) |

## Architecture

| Library | Purpose |
|---------|---------|
| Hilt (Dagger) | Dependency injection |
| Kotlin Coroutines + Flow | Async operations and reactive streams |
| ViewModel + StateFlow | MVVM state management |
| ProcessLifecycleOwner | App lifecycle detection (disappearing messages on close) |
| WorkManager | Scheduled background tasks (message sync, cleanup) |

## Utilities

| Library | Purpose |
|---------|---------|
| Timber | Structured logging |
| Gson | JSON serialization (MediaMessageEnvelope, P2PMessage) |
| ZXing | QR code generation and scanning |
| AndroidX ExifInterface | EXIF metadata reading (orientation) and stripping (privacy) |

## KMM Shared Module (`:shared`)

| Component | Version | Purpose |
|-----------|---------|---------|
| Kotlin Multiplatform | 1.9.24 | Targets: androidTarget + jvm("desktop") |
| kotlinx-coroutines | 1.7.3 | Async + Flow in commonMain; coroutines-swing in desktopMain |
| kotlinx-serialization | 1.6.0 | JSON serialization in shared models |
| kotlinx-datetime | 0.5.0 | Platform-independent timestamps |
| libsignal-android | 0.44.0 | Signal Protocol (androidMain) |

## Desktop App (`:desktopApp`)

| Library | Purpose |
|---------|---------|
| Compose Desktop 1.6.11 | Declarative UI for Linux/Windows |
| Exposed ORM 0.45.0 | SQLite schema and queries |
| sqlite-jdbc 3.45.3.0 | SQLite driver for Exposed |
| Ktor 2.3.7 (CIO + WebSockets) | Relay server WebSocket client |
| ZXing 3.5.2 | QR code generation for device-link display |
| libsignal-client 0.44.0 | Signal Protocol (desktop JVM) |
| libsecret (via `secret-tool` CLI) | OS keyring integration (GNOME Keyring / KWallet) |

## Known Dependency Conflicts

- **tor-android** pulls Kotlin stdlib 2.3.0 transitively. Must exclude `org.jetbrains.kotlin` group from the dependency to avoid version conflicts with Kotlin 1.9.24.
- **jtorctl** `EventHandler.newDescriptors` uses `MutableList<String>?` signature, not `java.util.List`.
- **Compose BOM 2023.10.01** uses `LinearProgressIndicator(progress: Float)` (not the lambda overload introduced later).
- **Compose Desktop 1.6.11** uses `VerticalDivider` / `HorizontalDivider`; `Divider` is deprecated. `Icons.Default.Send` is deprecated — use `Icons.AutoMirrored.Filled.Send`.
- **iOS targets** in `:shared` trigger Kotlin/Native toolchain download (blocked by `FAIL_ON_PROJECT_REPOS`). Comment them out on Linux CI; they require macOS/Xcode.
