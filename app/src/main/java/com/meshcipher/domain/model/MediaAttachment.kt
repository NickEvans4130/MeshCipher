package com.meshcipher.domain.model

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

enum class MediaType {
    IMAGE, VIDEO, VOICE
}
