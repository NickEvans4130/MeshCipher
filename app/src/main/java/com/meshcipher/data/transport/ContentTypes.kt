package com.meshcipher.data.transport

/** Relay message content types shared across Android and desktop. */
object ContentTypes {
    const val TEXT = 0
    const val MEDIA = 1
    const val DEVICE_LINK = 10
    const val CONTACT_SYNC = 11
    const val FORWARDED = 12
    const val DEVICE_UNLINK = 13
    const val MEDIA_FORWARDED = 14
    const val DESKTOP_MSG = 15
    const val DESKTOP_SEND_REQUEST = 16
    const val DESKTOP_MEDIA_REQUEST = 17
    // GAP-06 / R-06: Two-sided confirmation handshake for device linking
    const val LINK_CONFIRM_REQUEST = 18  // Android → Desktop: confirmation request (signed)
    const val LINK_CONFIRMED = 19        // Desktop → Android: user confirmed
    const val LINK_DENIED = 20           // Desktop → Android: user denied
}
