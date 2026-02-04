# Media Handling & IPFS

MeshCipher supports sending high-fidelity media (Photos, Videos, Audio) securely and efficiently, even over constrained networks like Bluetooth Mesh.

## The Challenge

Sending large files over a Bluetooth Mesh is impractical due to low bandwidth and high latency. Additionally, centralized storage servers compromise privacy.

## The Solution: IPFS + Chunking

We use the InterPlanetary File System (IPFS) combined with a chunking encrypt-then-upload strategy.

### Workflow

1.  **Selection**: User selects a file (e.g., 50MB Video).
2.  **Compression**:
    *   Images are resized to max 2048px (JPEG 85%).
    *   Videos are compressed using hardware acceleration.
3.  **Chunking**: The file is split into fixed-size **64KB chunks**.
    *   Why 64KB? Small enough to be reliable over poor connections, large enough to minimize overhead.
4.  **Encryption**:
    *   A random AES-256 key is generated **for each chunk**.
    *   The chunk is encrypted.
5.  **Distribution (IPFS)**:
    *   Encrypted chunks are added to the local IPFS node.
    *   They are hashed and given a CID (Content Identifier).
6.  **Metadata Message**:
    *   A regular Signal-encrypted message is sent to the recipient.
    *   **Content**: List of CIDs, Decryption Keys for each chunk, MIME type, and Thumbnail.
7.  **Retrieval**:
    *   Recipient receives the metadata message.
    *   App fetches chunks from IPFS (connecting to peers or the sender natively).
    *   Chunks are decrypted on the fly and streamed to a local temporary file.

### Privacy Implications

*   **Deniability**: Since the chunks on IPFS are encrypted with random keys (not derived from the user's main key), hosting a chunk does not prove knowledge of its contents.
*   **Decentralization**: No central server holds the user's media. It lives in the mesh (on devices) and on voluntary IPFS nodes.

### Performance Optimizations

*   **Thumbnails**: A small (<10KB) encrypted thumbnail is embedded directly in the initial signal message so the UI is responsive immediately.
*   **Pinning**: The sender's device "pins" the chunks to ensure availability until the recipient confirms download.
