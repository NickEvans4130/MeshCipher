# MeshCipher

Multi-transport encrypted messaging app for Android.

## Architecture

MeshCipher follows Clean Architecture with the following layers:

- **Domain** - Business models, repository interfaces, and use cases
- **Data** - Room database with SQLCipher encryption, Signal Protocol integration, repository implementations
- **Presentation** - Jetpack Compose UI with ViewModels (Phase 2)
- **DI** - Hilt dependency injection modules

## Tech Stack

- Kotlin
- Jetpack Compose
- Room + SQLCipher
- Signal Protocol (libsignal-android)
- Hilt (Dependency Injection)
- Kotlin Coroutines + Flow

## Building

```bash
./gradlew assembleDebug
```

## Testing

```bash
./gradlew test
```

## Project Status

- Phase 1: Foundation - Complete
- Phase 2: UI & Navigation - Pending
- Phase 3: Transport Layer - Pending
