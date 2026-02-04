package com.meshcipher.domain.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Complete metadata for a media file, including IPFS CIDs for all chunks.
 * This metadata is encrypted and sent as part of the message.
 */
data class MediaMetadata(
    @SerializedName("id")
    val id: String,

    @SerializedName("type")
    val mediaType: MediaType,

    @SerializedName("fileName")
    val fileName: String,

    @SerializedName("mimeType")
    val mimeType: String,

    @SerializedName("fileSize")
    val fileSize: Long,

    @SerializedName("chunkCount")
    val chunkCount: Int,

    @SerializedName("chunkCids")
    val chunkCids: List<String>,

    @SerializedName("chunkHashes")
    val chunkHashes: List<String>,

    @SerializedName("chunkKeys")
    val chunkKeys: List<String>,

    @SerializedName("chunkNonces")
    val chunkNonces: List<String>,

    @SerializedName("thumbnailCid")
    val thumbnailCid: String? = null,

    @SerializedName("thumbnailKey")
    val thumbnailKey: String? = null,

    @SerializedName("thumbnailNonce")
    val thumbnailNonce: String? = null,

    @SerializedName("width")
    val width: Int? = null,

    @SerializedName("height")
    val height: Int? = null,

    @SerializedName("durationMs")
    val durationMs: Long? = null
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): MediaMetadata? {
            return try {
                Gson().fromJson(json, MediaMetadata::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
}
