package com.meshcipher.desktop.data

import kotlinx.serialization.Serializable

const val CONTENT_TYPE_MESSAGE = 0
const val CONTENT_TYPE_DEVICE_LINK = 10
const val CONTENT_TYPE_CONTACT_SYNC = 11
const val CONTENT_TYPE_FORWARDED = 12
const val CONTENT_TYPE_DEVICE_UNLINK = 13
const val CONTENT_TYPE_MEDIA_FORWARDED = 14
const val CONTENT_TYPE_DESKTOP_MSG = 15
const val CONTENT_TYPE_DESKTOP_SEND_REQUEST = 16
const val CONTENT_TYPE_DESKTOP_MEDIA_REQUEST = 17
// GAP-06 / R-06: Two-sided confirmation handshake for device linking
const val CONTENT_TYPE_LINK_CONFIRM_REQUEST = 18  // Android → Desktop: confirmation request
const val CONTENT_TYPE_LINK_CONFIRMED = 19         // Desktop → Android: user approved
const val CONTENT_TYPE_LINK_DENIED = 20            // Desktop → Android: user denied

@Serializable
data class ContactSyncPayload(
    val type: String = "contact_sync",
    val contacts: List<ContactSyncItem>
)

@Serializable
data class ContactSyncItem(
    val userId: String,
    val displayName: String,
    val publicKeyHex: String
)
