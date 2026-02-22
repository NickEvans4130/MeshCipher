package com.meshcipher.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contacts",
    indices = [Index("display_name")]
)
data class ContactEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "public_key") val publicKey: ByteArray,
    @ColumnInfo(name = "identity_key") val identityKey: ByteArray,
    @ColumnInfo(name = "signal_protocol_address") val signalProtocolAddress: String,
    @ColumnInfo(name = "last_seen") val lastSeen: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "onion_address") val onionAddress: String? = null,
    @ColumnInfo(name = "current_safety_number") val currentSafetyNumber: String? = null,
    @ColumnInfo(name = "verified_safety_number") val verifiedSafetyNumber: String? = null,
    @ColumnInfo(name = "safety_number_verified_at") val safetyNumberVerifiedAt: Long? = null,
    @ColumnInfo(name = "safety_number_changed_at") val safetyNumberChangedAt: Long? = null
)
