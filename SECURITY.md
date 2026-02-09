# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| Latest release | Yes |
| Previous release | Security fixes only |
| Older | No |

## Reporting a Vulnerability

MeshCipher takes security seriously. If you discover a vulnerability, please report it responsibly.

### How to Report

1. **Do NOT open a public GitHub issue** for security vulnerabilities.
2. Email your report to **[security email - to be configured]** with the subject line: `[MeshCipher Security] Brief description`.
3. Alternatively, use GitHub's private vulnerability reporting feature via the **Security** tab on this repository.

### What to Include

- Description of the vulnerability
- Steps to reproduce
- Affected component (e.g., Signal Protocol integration, transport layer, database encryption)
- Impact assessment (what an attacker could achieve)
- Suggested fix (if you have one)

### What to Expect

- **Acknowledgment**: Within 48 hours of your report
- **Assessment**: We will evaluate severity and confirm the vulnerability within 7 days
- **Fix timeline**: Critical vulnerabilities will be patched as soon as possible, typically within 14 days. Lower severity issues will be addressed in the next release cycle.
- **Credit**: You will be credited in the release notes unless you prefer to remain anonymous

### Scope

The following are in scope for vulnerability reports:

- **Cryptographic implementation** - Flaws in Signal Protocol integration, AES-256-GCM media encryption, key generation, or key storage
- **Transport security** - Issues in any of the five transport layers (relay, Tor, WiFi Direct, Bluetooth mesh, P2P Tor)
- **Data at rest** - SQLCipher configuration, EncryptedSharedPreferences usage, media file handling
- **Identity system** - Hardware key management, authentication bypass, identity spoofing
- **Message integrity** - Replay attacks, message tampering, sequence number bypass
- **Privacy leaks** - Metadata exposure, .onion address leakage, unintended data transmission
- **Relay server** - Authentication bypass, message interception, unauthorized access

### Out of Scope

- Vulnerabilities requiring physical access to an unlocked device
- Social engineering attacks
- Denial of service against the relay server
- Issues in third-party dependencies (report these to the upstream project, but let us know so we can assess impact)
- Theoretical attacks that require unrealistic computational resources

## Security Architecture Overview

For technical details on our security implementation, see:

- [Cryptography](docs/cryptography.md) - Signal Protocol, AES-256-GCM, hardware-backed identity
- [Privacy Features](docs/privacy.md) - Disappearing messages, replay prevention
- [Transport Layer](docs/networking.md) - Per-mode security properties

### Key Security Properties

| Property | Implementation |
|----------|---------------|
| End-to-end encryption | Signal Protocol (X3DH + Double Ratchet) |
| Forward secrecy | Double Ratchet key derivation |
| Persistent sessions | Signal Protocol state encrypted at rest in EncryptedSharedPreferences |
| Database encryption | SQLCipher (AES-256-CBC + HMAC-SHA256) |
| Media encryption (transport) | AES-256-GCM with per-message random keys |
| Media encryption (at rest) | Per-file AES-256-GCM with keys in EncryptedSharedPreferences |
| EXIF metadata stripping | GPS, timestamps, device info, author data removed from all images |
| Key storage | Android Keystore (hardware-backed, non-exportable) |
| Real-time delivery | WebSocket (WSS) with JWT authentication |
| Replay prevention | Per-sender message sequence tracking |
| Metadata protection | Tor relay mode (IP hiding) or P2P modes (no server) |
| Anonymous endpoints | P2P Tor via ED25519-V3 hidden services |

## Responsible Disclosure

We kindly ask that you:

- Give us reasonable time to fix the issue before public disclosure
- Do not access or modify other users' data
- Do not perform actions that could harm the availability of the service
- Act in good faith to avoid privacy violations and disruption

We will not take legal action against researchers who follow this policy.
