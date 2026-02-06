package com.meshcipher.domain.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class MediaMessageEnvelope(
    @SerializedName("mediaId") val mediaId: String,
    @SerializedName("mediaType") val mediaType: String,
    @SerializedName("fileName") val fileName: String,
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("fileSize") val fileSize: Long,
    @SerializedName("width") val width: Int? = null,
    @SerializedName("height") val height: Int? = null,
    @SerializedName("durationMs") val durationMs: Long? = null,
    @SerializedName("encryptionKey") val encryptionKey: String,
    @SerializedName("encryptionIv") val encryptionIv: String,
    @SerializedName("encryptedData") val encryptedData: String
) {
    companion object {
        private val gson = Gson()

        fun fromJson(json: String): MediaMessageEnvelope =
            gson.fromJson(json, MediaMessageEnvelope::class.java)
    }

    fun toJson(): String = gson.toJson(this)
}
