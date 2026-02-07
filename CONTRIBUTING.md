# Contributing to MeshCipher

Thank you for your interest in contributing to MeshCipher. This document covers the process for contributing code, reporting bugs, and suggesting features.

## Getting Started

### Prerequisites

- JDK 17 or 21
- Android Studio Hedgehog or newer
- Android SDK 34
- An Android device or emulator running API 26+

### Setup

1. Fork the repository
2. Clone your fork:
   ```bash
   git clone https://github.com/YOUR_USERNAME/MeshCipher.git
   cd MeshCipher
   ```
3. Build and run tests:
   ```bash
   JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:compileDebugKotlin --no-daemon
   JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest --no-daemon
   ```

## Reporting Bugs

Open a GitHub issue with:

- Steps to reproduce
- Expected vs actual behavior
- Device model and Android version
- Connection mode in use (Direct, Tor, WiFi Direct, Bluetooth Mesh, P2P Tor)
- Logs (if available, via `adb logcat | grep MeshCipher`)

**Security vulnerabilities** should NOT be reported as issues. See [SECURITY.md](SECURITY.md) for responsible disclosure.

## Suggesting Features

Open a GitHub issue with the `enhancement` label. Include:

- The problem your feature solves
- Proposed solution
- Which layer(s) of the architecture it would affect (presentation, domain, data)
- Any security or privacy implications

## Submitting Code

### Branch Naming

Create a feature branch from `develop`:

```
feat/short-description     # New features
fix/short-description      # Bug fixes
refactor/short-description # Code refactoring
docs/short-description     # Documentation changes
```

### Code Style

- **Language**: Kotlin (no Java)
- **Formatting**: Follow existing patterns in the codebase
- **Naming**: Standard Kotlin conventions (`camelCase` for functions/properties, `PascalCase` for classes)
- **Coroutines**: Use `suspend` functions and `Flow` for async operations. No callbacks.
- **DI**: All dependencies injected via Hilt constructor injection. No service locators.
- **UI**: Jetpack Compose only. No XML layouts.

### Architecture Rules

MeshCipher follows Clean Architecture. Respect the layer boundaries:

- **`presentation/`** depends on **`domain/`** only. No direct data layer imports.
- **`domain/`** has zero Android framework dependencies. Pure Kotlin only.
- **`data/`** implements `domain/` interfaces. Contains all Android/framework code.
- **ViewModels** expose `StateFlow` and accept events via public methods. No LiveData.
- **Repository** implementations are singletons provided by Hilt.

### Cryptography Guidelines

Changes to cryptographic code require extra scrutiny:

- Do not modify Signal Protocol integration without thorough justification
- Use `java.util.Base64` (not `android.util.Base64`) in data layer classes to maintain unit testability
- Media encryption must use AES-256-GCM with per-message random keys
- Never log or expose encryption keys, IVs, or plaintext content
- Hardware key operations must go through `KeyManager` and Android Keystore

### Transport Layer Guidelines

- New transports should implement a consistent interface matching existing transports
- All transports receive already-encrypted payloads (Signal Protocol encryption happens before transport selection)
- The `TransportManager.sendWithFallback()` method handles fallback ordering. New transports need to be integrated into the priority chain for each `ConnectionMode`.
- `contentType` routing: `0` = text, `1` = media. Media may require chunking or special handling depending on the transport.

### Testing

- Write unit tests for new business logic
- Place tests in `app/src/test/java/com/meshcipher/` mirroring the main source structure
- Use MockK for mocking Android framework classes
- Run the full test suite before submitting:
  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest --no-daemon
  ```
- There is 1 known pre-existing test failure (`TorManagerTest.getOrbotInstallIntent` - `Uri.parse` not mocked). Do not attempt to fix this in unrelated PRs.

### Commit Messages

- Use conventional commit format: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`
- Include the scope in parentheses when applicable: `feat(transport):`, `fix(media):`
- Keep the subject line under 72 characters
- Use the body for additional context if needed

### Pull Request Process

1. Ensure your branch is up to date with `develop`
2. All tests pass
3. No new compiler warnings introduced
4. PR description includes:
   - Summary of changes
   - Motivation / issue reference
   - Testing performed
   - Any security implications
5. Request review from a maintainer
6. Address review feedback
7. PR will be squash-merged into `develop`

## Project Structure Reference

See the [Architecture documentation](docs/architecture.md) for a full breakdown of packages and their responsibilities.

Key directories for common contribution areas:

| Area | Directory |
|------|-----------|
| UI screens | `presentation/{feature}/` |
| UI components | `presentation/components/` |
| Transport layers | `data/transport/` |
| Database schema | `data/local/entity/`, `data/local/database/` |
| Encryption | `data/crypto/`, `data/media/` |
| Bluetooth mesh | `data/bluetooth/` |
| WiFi Direct | `data/wifidirect/` |
| Tor integration | `data/tor/` |
| Domain models | `domain/model/` |

## Questions

If you have questions about contributing, open a GitHub issue with the `question` label or start a discussion in the Discussions tab.
