package com.meshcipher.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "linked_devices")
data class LinkedDeviceEntity(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "device_name") val deviceName: String,
    @ColumnInfo(name = "device_type") val deviceType: String,
    @ColumnInfo(name = "public_key_hex") val publicKeyHex: String,
    @ColumnInfo(name = "linked_at") val linkedAt: Long,
    @ColumnInfo(name = "approved") val approved: Boolean
)
