package com.meshcipher.domain.model

enum class MediaType {
    PHOTO,
    VIDEO,
    AUDIO,
    FILE;

    companion object {
        fun fromMimeType(mimeType: String): MediaType {
            return when {
                mimeType.startsWith("image/") -> PHOTO
                mimeType.startsWith("video/") -> VIDEO
                mimeType.startsWith("audio/") -> AUDIO
                else -> FILE
            }
        }
    }

    fun getMimeType(): String {
        return when (this) {
            PHOTO -> "image/jpeg"
            VIDEO -> "video/mp4"
            AUDIO -> "audio/m4a"
            FILE -> "application/octet-stream"
        }
    }

    fun getFileExtension(): String {
        return when (this) {
            PHOTO -> ".jpg"
            VIDEO -> ".mp4"
            AUDIO -> ".m4a"
            FILE -> ".bin"
        }
    }
}
