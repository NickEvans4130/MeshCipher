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

Safety numbers allow users to verify that no man-in-the-middle has tampered with their Signal Protocol session.

**Algorithm** (`SafetyNumberGenerator` in the `:shared` KMM module):

```
1. Canonically order both parties (lexicographic on userId to ensure identical output on both sides)
2. input = userId1 + publicKey1 + userId2 + publicKey2  (canonically ordered)
3. hash = SHA-512(input), iterated 5200 times
4. Output: 120-digit decimal string derived from the final hash bytes
```

**Key rotation detection** (`SafetyNumberManager`):

- `MeshCipherApplication.checkAllSafetyNumbers()` recomputes safety numbers for all contacts at startup
- If `currentSafetyNumber` differs from `verifiedSafetyNumber`, the contact is flagged as changed
- A warning banner appears in ChatScreen; ContactDetailScreen shows a red verify button
- `safetyNumberChangedAt` records when the mismatch was first detected

**Verification UI**: `VerifySafetyNumberScreen` displays both numbers for side-by-side comparison. On confirmation, `verifiedSafetyNumber` is updated to match `currentSafetyNumber` and the warning is cleared.

## 3. Data at Rest Encryption

### Database (SQLCipher)

- **Algorithm**: AES-256 in CBC mode with HMAC-SHA256 page authentication
- **Key Derivation**: `DatabaseKeyProvider` generates a random 32-byte key via `SecureRandom` on first launch
- **Key Storage**: Key stored base64-encoded in EncryptedSharedPreferences (`db_key_prefs`), backed by Android Keystore AES-256-GCM master key
- **Implementation**: Room database wraps SQLCipher via `SupportFactory(passphrase)`
- **Result**: All messages, contacts, and conversations encrypted on disk. Database file is opaque binary without the key. The encryption key is hardware-bound through the Android Keystore chain.

### Signal Protocol State (`SignalProtocolStoreImpl`)

All Signal Protocol state is persisted in EncryptedSharedPreferences (`signal_protocol_store`), backed by the Android Keystore AES-256-GCM master key:

- **Identity key pair**: Curve25519 key pair generated once on first launch, persisted as Base64-encoded serialized bytes
- **Registration ID**: Random 32-bit integer, persisted alongside identity key pair
- **Sessions**: Keyed by `session:{name}:{deviceId}`, serialized `SessionRecord` bytes
- **Pre-keys**: Keyed by `prekey:{id}`, serialized `PreKeyRecord` bytes
- **Signed pre-keys**: Keyed by `signed_prekey:{id}`, serialized `SignedPreKeyRecord` bytes
- **Identity keys (remote)**: Keyed by `identity:{name}:{deviceId}`, serialized `IdentityKey` bytes
- **Sender keys**: Keyed by `sender_key:{name}:{deviceId}:{distributionId}`, serialized `SenderKeyRecord` bytes
- **Kyber pre-keys**: Keyed by `kyber_prekey:{id}`, serialized `KyberPreKeyRecord` bytes

Sessions survive app restarts. The identity key pair is stable for the lifetime of the installation. All records are encrypted at rest via the EncryptedSharedPreferences AES-256-GCM layer.

### Media Files (`MediaFileManager`)

Media files are encrypted at rest using per-file AES-256-GCM encryption before being written to disk.

- **Directory**: `context.filesDir/media_encrypted/{type}/{mediaId}`
- **Encryption**: Each file gets a unique 256-bit AES key and 96-bit IV generated via `SecureRandom`
- **Key storage**: Per-file keys and IVs stored in EncryptedSharedPreferences (`media_encryption_keys`), keyed by `key:{mediaId}` and `iv:{mediaId}`
- **Decryption**: `decryptMedia(mediaId, mediaType)` returns plaintext `ByteArray` in memory; `decryptMediaToTempFile()` writes a temporary file for video/voice playback
- **Display**: Images are decrypted to byte arrays and rendered via `BitmapFactory.decodeByteArray()`. Video and voice are decrypted to temporary cache files for `MediaPlayer`/`VideoView`.
- **Cleanup**: `deleteMedia()` removes both the encrypted file and the corresponding keys from EncryptedSharedPreferences. `cleanupAllMedia()` also removes any legacy plaintext files under `media/`.

Plaintext media never exists on the filesystem. The only unencrypted copies are transient in-memory byte arrays or temporary cache files created for playback.

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

## 6. EXIF Metadata Stripping

All images processed by `MediaProcessor` have identifying EXIF metadata stripped before encryption and sending. The following tag categories are removed:

- **GPS / Location**: Latitude, longitude, altitude, speed, destination coordinates, timestamps, processing method, area information
- **Date / Time**: Original datetime, digitized datetime, timezone offsets
- **Device / Software**: Camera make, model, software version, serial numbers (body + lens), lens make/model, camera owner name
- **Author / Copyright**: Artist, copyright, user comment, image description
- **Thumbnails**: Thumbnail dimensions (thumbnails can contain their own EXIF with location data)

EXIF orientation is read and applied to the bitmap before stripping, so photos are correctly rotated despite the orientation tag being removed. This is done via `ExifInterface` from the AndroidX exifinterface library.

## 7. Network Privacy (Metadata Protection)

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

## Post-Quantum Cryptography (PQXDH) — RM-10 / GAP-08 / R-09

### Motivation

All classical Signal Protocol sessions use X25519 (X3DH) for key agreement. A sufficiently capable quantum computer could break X25519, exposing all previously captured ciphertext ("harvest-now-decrypt-later" attack). MeshCipher's target users — journalists, activists, first responders — generate communications whose long-term confidentiality is critical.

### Hybrid Key Agreement (PQXDH)

MeshCipher implements PQXDH (Post-Quantum Extended Diffie-Hellman) as specified at https://signal.org/docs/specifications/pqxdh/. PQXDH extends X3DH by adding a CRYSTALS-Kyber-1024 KEM alongside the classical X25519 exchange.

The combined session key is:

```
K = KDF(X25519_output || Kyber_KEM_output)
```

Breaking session security requires breaking **both** X25519 and Kyber-1024 simultaneously. The KDF construction and Kyber encapsulation are handled internally by libsignal-client 0.44.0 when `SessionBuilder.process()` is called with a `PreKeyBundle` that includes Kyber keys.

### Key Generation

- Kyber-1024 key pair: `KEMKeyPair.generate(KEMKeyType.KYBER_1024)` via libsignal
- The Kyber public key is signed with the local ED25519 identity key: `Curve.calculateSignature(identityKey.privateKey, kyberPublicKey.serialize())`
- Kyber pre-keys are stored in `SignalProtocolStoreImpl` under the prefix `kyber_prekey:` in `EncryptedSharedPreferences` (AES-256-GCM at rest)
- Kyber private key material is never logged or transmitted

### Pre-Key Bundle Format

PQXDH-capable clients upload the following to the relay's `/api/v1/prekeys`:
- Classical EC pre-key (X25519)
- Signed pre-key (X25519, signed with identity key)
- Identity key (ED25519)
- Kyber-1024 pre-key (public key + ED25519 signature)

### Backwards Compatibility

If a contact's pre-key bundle does not include Kyber fields (`kyber_pre_key` is absent), `PreKeyManager.buildRemoteBundle()` builds a classical X3DH `PreKeyBundle` and `SessionBuilder.process()` falls back to X3DH. A warning is logged: "Remote bundle has no Kyber key — falling back to classical X3DH (session is not PQ-protected)".

This means PQXDH-capable clients can communicate with older clients. However, sessions with older clients are not post-quantum protected.

### Implementation Files

- `data/encryption/PreKeyManager.kt` — Kyber pre-key generation, storage, bundle construction
- `data/encryption/SignalProtocolStoreImpl.kt` — `KyberPreKeyStore` implementation (prefix `kyber_prekey:`)
- `data/encryption/SignalProtocolManager.kt` — session creation via `SessionBuilder.process()`
- `domain/usecase/UploadPreKeyBundleUseCase.kt` — uploads PQXDH bundle to relay
- `relay-server/server.py` — `StoredPreKeyBundle` model, `/api/v1/prekeys` POST + GET
