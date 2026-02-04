# Data Storage

MeshCipher persists data locally on the Android device using a secure, encrypted database.

## Database Technology

*   **Engine**: SQLite
*   **ORM**: Room Persistence Library
*   **Encryption**: SQLCipher (AES-256)

## Database Schema

The database consists of three primary tables relating to the core chat functionality.

### 1. Contacts Table (`contacts`)
Stores information about known users.

| Column | Type | Description |
| :--- | :--- | :--- |
| `id` | String (PK) | The internal UUID for the contact. |
| `user_id` | String | The public-key derived ID (visible to user). |
| `public_key` | Blob | The raw Ed25519 public key. |
| `display_name`| String | User-assigned local name for the contact. |
| `is_verified` | Boolean | True if QR code verification was improved. |

### 2. Conversations Table (`conversations`)
Represents a chat thread between the user and a contact.

| Column | Type | Description |
| :--- | :--- | :--- |
| `id` | String (PK) | UUID. |
| `contact_id` | String (FK) | Link to `contacts`. |
| `last_msg_id` | String | ID of the most recent message (for previews). |
| `unread_count`| Integer | Number of unread messages. |

### 3. Messages Table (`messages`)
Stores the actual message history.

| Column | Type | Description |
| :--- | :--- | :--- |
| `id` | String (PK) | UUID. |
| `conversation_id`| String (FK)| Link to `conversations`. |
| `sender_id` | String | ID of the sender. |
| `content` | Blob | **Encrypted** content (if locally stored encrypted) or serialized content. |
| `status` | Enum | `SENDING`, `SENT`, `DELIVERED`, `READ`, `FAILED`. |
| `timestamp` | Long | UNIX timestamp. |
| `media_cid` | String | (Optional) IPFS CID for media attachments. |

## Key Storage

Sensitivity keys are NOT stored in the database.

*   **Database Key**: The key used to unlock SQLCipher is generated on first launch and stored in `EncryptedSharedPreferences`.
*   **Identity Keys**: The user's private Ed25519 identity key is stored in the **Android Keystore System**.
