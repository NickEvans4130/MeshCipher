package com.meshcipher.data.wifidirect

/**
 * Type IDs for the WiFi Direct binary wire protocol (GAP-07 / R-07).
 *
 * Wire format per message:
 *   [1 byte  — type ID (one of the constants below)]
 *   [4 bytes — payload length, big-endian signed int]
 *   [N bytes — payload bytes]
 *
 * Unknown type bytes are rejected by [WifiDirectMessageCodec] without attempting
 * to deserialise the payload, eliminating the Java-deserialization gadget-chain attack
 * surface that ObjectInputStream.readObject() exposes.
 */
object WifiDirectMessageType {
    const val TEXT_MESSAGE: Byte = 1
    const val FILE_TRANSFER: Byte = 2
    const val FILE_CHUNK: Byte = 3
    const val ACKNOWLEDGMENT: Byte = 4
}
