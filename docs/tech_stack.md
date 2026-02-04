# Tech Stack

MeshCipher leverages a modern Android technology stack designed for performance, security, and offline capabilities.

## Core Language & Runtime
*   **Kotlin**: The primary language for the entire codebase. Used for its conciseness, null-safety, and coroutine support.
*   **Coroutines & Flow**: Used for asynchronous programming and reactive streams.
    *   *Coroutines*: Used for one-shot async operations (database writes, network calls).
    *   *Flow*: Used for observing data changes (database updates, incoming messages).

## User Interface
*   **Jetpack Compose**: The modern toolkit for building native UI. It allows for declarative UI definitions.
*   **Material Design 3**: The visual design system used throughout the app.

## Data Persistence & Encryption
*   **Room Database**: The abstraction layer over SQLite.
*   **SQLCipher**: An extension to SQLite that provides transparent 256-bit AES encryption of the database file. This ensures that all local data is encrypted at rest.
*   **EncryptedSharedPreferences**: Used to store sensitive key-value pairs (like session tokens) securely using the Android Keystore.
*   **IPFS (InterPlanetary File System)**: Used for decentralized, off-chain storage of heavy media files.

## Cryptography & Security
*   **Libsignal (Signal Protocol)**: The gold standard for end-to-end encryption. Provides `Double Ratchet` algorithm for perfect forward secrecy and deniable authentication.
*   **Android Keystore System**: Hardware-backed storage for private keys.
*   **Bouncy Castle**: Used for additional cryptographic primitives not available in the standard library.

## Networking & Transport
*   **Retrofit + OkHttp**: For standard REST API communication with the relay server.
*   **Bluetooth Low Energy (BLE)**: Used for the offline mesh network.
*   **Orbot / Tor**: Integrated for anonymizing internet traffic.

## Architecture & Tools
*   **Hilt**: Dependency Injection framework built on top of Dagger.
*   **Timber**: For logging.
*   **Gradle (Kotlin DSL)**: Build system.
