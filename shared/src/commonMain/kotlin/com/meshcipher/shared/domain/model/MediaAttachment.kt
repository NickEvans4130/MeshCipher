package com.meshcipher.shared.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MediaAttachment(
    val mediaId: String,
    val mediaType: MediaType,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val localPath: String?,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val encryptionKey: String,
    val encryptionIv: String
)

@Serializable
enum class MediaType {
    IMAGE, VIDEO, VOICE
}
