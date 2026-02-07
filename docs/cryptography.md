# Cryptography

## 1. Hardware-Bound Identity

MeshCipher uses cryptographic identities tied to device hardware instead of phone numbers or email addresses.

### Key Generation

- **Algorithm**: ECDSA on secp256r1 (P-256) with SHA-256
- **Storage**: Private keys generated inside the Android Keystore (TEE/StrongBox where available). Keys are marked non-exportable and never leave the secure hardware.
- **Signing**: `SHA256withECDSA` via `java.security.Signature`
- **User Authentication**: Biometric authentication required (30-second validity window), invalidated on new biometric enrollment
- **User ID Derivation**:
  ```
  publicKey = EC_P256.generateKeyPair().public
  hash = SHA-256(publicKey.encoded)
  userId = Base64UrlEncode(hash).substring(0, 32)
  ```

### Identity Storage (`IdentityStorage`)

- Identity metadata (userId, deviceId, deviceName, createdAt) persisted in EncryptedSharedPreferences
- Hardware public key stored alongside metadata
- `KeyManager` handles Android Keystore operations (generate, sign, delete)

### Authentication

Challenge-response protocol with cryptographic signature verification:

1. Client sends `userId` and base64-encoded public key to `/api/v1/auth/challenge`
2. Server generates 32-byte random challenge, stores it with the public key, returns base64-encoded challenge
3. Client signs challenge bytes with hardware-backed ECDSA key (`SHA256withECDSA`)
4. Client sends `userId` and base64-encoded signature to `/api/v1/auth/verify`
5. Server deserializes the DER-encoded EC public key, verifies the ECDSA signature against the stored challenge using `cryptography.hazmat.primitives.asymmetric.ec.ECDSA(SHA256)`
6. On success, server issues a JWT (HS256, 30-day expiry) containing `user_id`, `iat`, `exp`

- **Token Storage**: JWT tokens stored in EncryptedSharedPreferences (`TokenStorage`)
- **Token Injection**: `AuthInterceptor` (OkHttp interceptor) attaches `Authorization: Bearer <token>` to all relay API requests, skipping public endpoints (`/auth/`, `/health`, `/register`)
- **Challenge Expiry**: Challenges expire after 5 minutes; expired entries are housekept on each new challenge request

## 2. End-to-End Encryption (Signal Protocol)

All message content is encrypted using the Signal Protocol via `libsignal-client`.

### Session Establishment

```
SignalProtocolManager.createSession(contact, preKeyBundle)
  -> SessionBuilder(signalProtocolStore, contact.signalProtocolAddress)
  -> sessionBuilder.process(preKeyBundle)
```

**PreKeyBundle** contains:
- Identity key (Curve25519 public key)
- Registration ID
- Signed pre-key (ID + public key + signature)
- One-time pre-key (ID + public key)

**X3DH (Extended Triple Diffie-Hellman)** performs the initial key agreement. Users do not need to be online simultaneously - pre-keys allow asynchronous session setup.

### Message Encryption

```
SignalProtocolManager.encryptMessage(plaintext, recipientAddress)
  -> SessionCipher(signalProtocolStore, recipientAddress)
  -> sessionCipher.encrypt(plaintext.toByteArray())
  -> Returns CiphertextMessage
```

**First message**: `PreKeySignalMessage` (type `PREKEY_TYPE`) - establishes session
**Subsequent messages**: `SignalMessage` (type `WHISPER_TYPE`) - uses established Double Ratchet session

### Message Decryption

```
SignalProtocolManager.decryptMessage(ciphertext, senderAddress)
  -> SessionCipher(signalProtocolStore, senderAddress)
  -> sessionCipher.decrypt(ciphertext)  // dispatches by type
  -> Returns plaintext String
```

### Double Ratchet Properties

- **Perfect Forward Secrecy (PFS)**: Each message uses a new symmetric key derived from the ratchet. Compromise of current keys cannot decrypt past messages.
- **Post-Compromise Security**: After a key compromise, the ratchet advances with new DH exchanges, re-establishing security.
- **Deniable Authentication**: The protocol does not produce cryptographic proof that a specific user sent a message.

### Signal Protocol Address Format

```
signalProtocolAddress = "userId@deviceId"
```

Stored in `ContactEntity.signalProtocolAddress`. Used as the addressing scheme for all Signal Protocol operations.

### Safety Number Verification

```
SignalProtocolManager.getSafetyNumber(localIdentityKey, remoteIdentityKey)
  -> SHA-256(localKey + remoteKey)
  -> Returns 12-digit numeric string
```

Displayed in Contact Detail screen for manual out-of-band verification.

## 3. Data at Rest Encryption

### Database (SQLCipher)

- **Algorithm**: AES-256 in CBC mode with HMAC-SHA256 page authentication
- **Key Derivation**: `DatabaseKeyProvider` generates a random 32-byte key via `SecureRandom` on first launch
- **Key Storage**: Key stored base64-encoded in EncryptedSharedPreferences (`db_key_prefs`), backed by Android Keystore AES-256-GCM master key
- **Implementation**: Room database wraps SQLCipher via `SupportFactory(passphrase)`
- **Result**: All messages, contacts, and conversations encrypted on disk. Database file is opaque binary without the key. The encryption key is hardware-bound through the Android Keystore chain.

### Media Files

Media files at rest on the filesystem are stored in their decrypted form under `context.filesDir/media/{type}/{mediaId}`. The encrypted form exists only during transport. This is a deliberate trade-off: once decrypted, media is available for the UI without re-decryption overhead. When disappearing messages trigger cleanup, the cleanup manager deletes both the database record and the media file.

## 4. Media Encryption (Transport)

Media is encrypted per-message before transport using AES-256-GCM.

### Encryption (`MediaEncryptor.encrypt`)

```
keyGen = KeyGenerator.getInstance("AES")
keyGen.init(256)
secretKey = keyGen.generateKey()

cipher = Cipher.getInstance("AES/GCM/NoPadding")
cipher.init(ENCRYPT_MODE, secretKey)
iv = cipher.iv   // random 12-byte IV from cipher
encryptedBytes = cipher.doFinal(plainBytes)

return EncryptedMedia(
    encryptedBytes,
    Base64(secretKey.encoded),  // 256-bit key
    Base64(iv)                  // 96-bit IV
)
```

### Decryption (`MediaEncryptor.decrypt`)

```
keyBytes = Base64.decode(encryptionKey)
ivBytes = Base64.decode(encryptionIv)

secretKey = SecretKeySpec(keyBytes, "AES")
gcmSpec = GCMParameterSpec(128, ivBytes)  // 128-bit auth tag

cipher = Cipher.getInstance("AES/GCM/NoPadding")
cipher.init(DECRYPT_MODE, secretKey, gcmSpec)
return cipher.doFinal(encryptedBytes)
```

### Key Distribution

The AES key and IV are included in the `MediaMessageEnvelope` JSON, which is itself encrypted by the Signal Protocol before transport. The media encryption key never travels in the clear.

## 5. Message Sequence Tracking

`MessageSequenceTracker` prevents replay attacks by tracking per-sender message sequence numbers.

- Backed by SharedPreferences (`message_sequences`)
- Each sender has a counter: `{senderId} -> lastSequenceNumber`
- Messages with sequence numbers <= last seen are rejected
- Prevents re-delivery of previously processed messages

## 6. Network Privacy (Metadata Protection)

### Direct Mode
- Content: encrypted (Signal Protocol)
- Metadata: IP address visible to relay server, timing correlation possible
- Server sees: sender ID, recipient ID, message size, timestamp

### Tor Relay Mode
- Content: encrypted (Signal Protocol)
- Metadata: IP hidden from relay server (exit node IP visible instead)
- Traffic passes through 3 Tor hops before reaching relay
- OkHttp client configured with SOCKS5 proxy at `127.0.0.1:9050`

### P2P Tor Mode
- Content: encrypted (Signal Protocol)
- Metadata: both endpoints hidden behind .onion addresses
- No relay server involved at all
- Tor provides 6 hops (3 per side of the rendezvous circuit)

### WiFi Direct / Bluetooth Mesh
- Content: encrypted (Signal Protocol)
- Metadata: no network trace (no ISP, no server logs)
- Communication is device-to-device only
- WiFi Direct: MAC addresses visible to paired device
- Bluetooth Mesh: BLE advertisements contain hashed device/user IDs
