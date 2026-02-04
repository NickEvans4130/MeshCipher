# Networking & Transport

MeshCipher employs a hybrid transport system that automatically selects the best available method for message delivery.

## Transport Modes

### 1. Direct Internet (Relay Server)
*   **Protocol**: HTTPS / WebSocket.
*   **Use Case**: High-speed, reliable delivery when both users have internet access.
*   **Privacy**: Content is encrypted, but metadata (IP, timing) is visible to the server unless TOR is used.

### 2. TOR Network
*   **Protocol**: SOCKS5 via Orbot.
*   **Use Case**: Privacy-critical communication.
*   **Mechanism**: Traffic is routed through three random nodes in the Tor network. The Relay Server sees the exit node's IP, not the user's.

### 3. Bluetooth Mesh (P2P / Offline)
*   **Protocol**: Bluetooth Low Energy (BLE) + GATT.
*   **Use Case**: Disaster scenarios, protests, or remote areas with no internet coverage.
*   **Mechanism**: Store-and-forward flooding mesh.

## Bluetooth Mesh Implementation Details

The Bluetooth Mesh implementation is custom-built for high reliability and battery efficiency.

### Discovery & Advertising
*   **Advertising**: Devices broadcast custom BLE advertisements containing a hash of their Device ID and User ID.
*   **Scanning**: Devices scan for these advertisements to populate a generic "Neighbor Table".
*   **Handshake**: A GATT connection is established to verify identity before exchanging messages.

### Message Routing
*   **Flooding with TTL**: Messages are flooded to neighbors with a Time-To-Live (TTL) counter (default: 5 hops).
*   **Loop Prevention**: Every message has a unique UUID. Devices maintain a "Seen Messages" cache (Bloom Filter) to ignore duplicates.
*   **GATT Characteristics**:
    *   `Message Characteristic` (Write-only): For incoming encrypted packets.
    *   `ACK Characteristic` (Notify): For delivery receipts.

### Protocol Format
```
[Header: 2 bytes] [MessageID: 16 bytes] [DestUserID: 32 bytes] [TTL: 1 byte] [Payload: Var]
```

## Transport Selection Logic

The `TransportManager` selects the transport based on:
1.  **Recipient Availability**: Is the user reachable via Bluetooth? (Check neighbor table).
2.  **Internet Connectivity**: Is the device online?
3.  **User Preference**: Has the user forced "Tor Only" mode?

Priority Order:
1.  **Bluetooth Mesh** (If recipient is nearby/reachable via mesh) -> Preferred for privacy and speed if local.
2.  **Tor** (If enabled and internet available).
3.  **Direct Internet** (Fallback).
