package com.meshcipher.data.local.database

import android.util.Base64
import androidx.room.TypeConverter

class Converters {

    @TypeConverter
    fun fromByteArray(value: ByteArray): String {
        return Base64.encodeToString(value, Base64.NO_WRAP)
    }

    @TypeConverter
    fun toByteArray(value: String): ByteArray {
        return Base64.decode(value, Base64.NO_WRAP)
    }
}
