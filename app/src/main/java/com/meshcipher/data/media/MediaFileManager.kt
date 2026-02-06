package com.meshcipher.data.media

import android.content.Context
import com.meshcipher.domain.model.MediaType
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaFileManager @Inject constructor() {

    fun saveMedia(context: Context, mediaId: String, bytes: ByteArray, mediaType: MediaType): String {
        val dir = getMediaDir(context, mediaType)
        val file = File(dir, mediaId)
        file.writeBytes(bytes)
        Timber.d("Saved media %s (%d bytes) to %s", mediaId, bytes.size, file.absolutePath)
        return file.absolutePath
    }

    fun getMediaFile(context: Context, mediaId: String, mediaType: MediaType): File? {
        val file = File(getMediaDir(context, mediaType), mediaId)
        return if (file.exists()) file else null
    }

    fun deleteMedia(context: Context, mediaId: String, mediaType: MediaType) {
        val file = File(getMediaDir(context, mediaType), mediaId)
        if (file.exists()) {
            file.delete()
            Timber.d("Deleted media: %s", mediaId)
        }
    }

    fun getMediaDir(context: Context, mediaType: MediaType): File {
        val dir = File(context.filesDir, "media/${mediaType.name.lowercase()}")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun cleanupAllMedia(context: Context) {
        val mediaRoot = File(context.filesDir, "media")
        if (mediaRoot.exists()) {
            mediaRoot.deleteRecursively()
            Timber.d("Cleaned up all media files")
        }
    }
}
