# Security & Cryptography

This document outlines the security architecture of MeshCipher, detailing how we protect user data, identity, and communication.

## 1. Hardware-Bound Identity

MeshCipher eschews traditional phone number-based identities in favor of cryptographic identities tied to the user's device hardware.

### Key Generation
*   **Algorithm**: Ed25519 (Edwards-curve Digital Signature Algorithm).
*   **Storage**: Private keys are generated inside the **Android Keystore** (TEE/StrongBox) and marked as non-exportable. They can never leave the physical device.
*   **User ID**: The User ID is derived from the SHA-256 hash of the public key, Base64 encoded.

### Authentication
*   **Biometrics**: Operations using the private key (like signing a login challenge) require biometric authentication (Fingerprint/Face Unlock).
*   **Challenge-Response**: Authentication with the relay server uses a challenge-response mechanism signed by the hardware key.

## 2. End-to-End Encryption (Signal Protocol)

All communications (Direct or Group) are encrypted using the Signal Protocol.

*   **X3DH (Extended Triple Diffie-Hellman)**: Used for the initial key agreement between users. Allows for asynchronous setup (users don't need to be online at the same time).
*   **Double Ratchet Algorithm**: Used for message encryption. It provides:
    *   **Perfect Forward Secrecy (PFS)**: Compromise of current keys does not reveal past messages.
    *   **Post-Compromise Security**: Compromise of current keys does not allow decryption of future messages (once the ratchet advances).

## 3. Data at Rest Encryption

Local data is protected against physical device seizure or malware.

### Database Encryption
*   **SQLCipher**: The Room database is encrypted with 256-bit AES.
*   **Key Management**: The database encryption key is derived from a secret stored in `EncryptedSharedPreferences`.

### Media Encryption
*   **Chunked Encryption**: Large media files are split into 64KB chunks.
*   **Per-Chunk Keys**: Each chunk is encrypted with a unique random AES-256 key.
*   **Key Distribution**: The keys for the chunks are encrypted using the Signal Protocol and sent to the recipient in the metadata message.
*   **Storage**: Encrypted chunks are stored on IPFS. Even if the IPFS node is public, the data is unreadable without the keys found in the E2E encrypted message.

## 4. Social Recovery (Shamir's Secret Sharing)

To prevent permanent account loss due to device loss (since keys are hardware-bound), MeshCipher implements Social Recovery.

*   **Mechanism**: The master recovery seed is split into 5 "shards".
*   **Threshold**: A threshold of 3 shards is required to reconstruct the seed.
*   **Guardians**: The shards are encrypted and distributed to 5 trusted contacts ("Guardians").
*   **Recovery**: Authorization from 3 guardians allows the user to provision a new device identity linked to their account.

## 5. Network Privacy (Metadata Protection)

*   **TOR Integration**: Network traffic can be routed through the TOR network via Orbot integration. This hides the user's IP address from the relay server, protecting metadata (who is talking to whom).
*   **Bluetooth Mesh**: Completely bypasses the internet, leaving no metadata trail on any ISP or server logs.
