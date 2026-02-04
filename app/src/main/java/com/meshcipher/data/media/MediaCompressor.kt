package com.meshcipher.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles photo and video compression, and thumbnail generation.
 */
@Singleton
class MediaCompressor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Compresses a photo to JPEG with reduced dimensions.
     */
    suspend fun compressPhoto(uri: Uri): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(Exception("Cannot open image"))

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            // Calculate sample size for max dimension
            val maxDim = MAX_PHOTO_DIMENSION
            var sampleSize = 1
            if (options.outWidth > maxDim || options.outHeight > maxDim) {
                val halfWidth = options.outWidth / 2
                val halfHeight = options.outHeight / 2
                while ((halfWidth / sampleSize) >= maxDim || (halfHeight / sampleSize) >= maxDim) {
                    sampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }

            val stream2 = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(Exception("Cannot reopen image"))

            val bitmap = BitmapFactory.decodeStream(stream2, null, decodeOptions)
            stream2.close()

            if (bitmap == null) {
                return@withContext Result.failure(Exception("Failed to decode image"))
            }

            // Scale if still too large
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                val newWidth = (bitmap.width * scale).toInt()
                val newHeight = (bitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }

            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, output)
            scaled.recycle()

            val result = output.toByteArray()
            Timber.d("Compressed photo: %d bytes", result.size)
            Result.success(result)
        } catch (e: Exception) {
            Timber.e(e, "Photo compression failed")
            Result.failure(e)
        }
    }

    /**
     * Reads raw bytes from a video URI (basic pass-through for now).
     */
    suspend fun processVideo(uri: Uri): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(Exception("Cannot open video"))

            val data = inputStream.readBytes()
            inputStream.close()

            Timber.d("Processed video: %d bytes", data.size)
            Result.success(data)
        } catch (e: Exception) {
            Timber.e(e, "Video processing failed")
            Result.failure(e)
        }
    }

    /**
     * Generates a thumbnail from an image.
     */
    suspend fun generatePhotoThumbnail(data: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return@withContext null

            val scale = THUMBNAIL_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
            val thumbWidth = (bitmap.width * scale).toInt()
            val thumbHeight = (bitmap.height * scale).toInt()

            val thumbnail = Bitmap.createScaledBitmap(bitmap, thumbWidth, thumbHeight, true)
            bitmap.recycle()

            val output = ByteArrayOutputStream()
            thumbnail.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, output)
            thumbnail.recycle()

            output.toByteArray()
        } catch (e: Exception) {
            Timber.w(e, "Thumbnail generation failed")
            null
        }
    }

    /**
     * Generates a thumbnail from a video URI.
     */
    suspend fun generateVideoThumbnail(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(0) ?: return@withContext null

            val scale = THUMBNAIL_SIZE.toFloat() / maxOf(frame.width, frame.height)
            val thumbWidth = (frame.width * scale).toInt()
            val thumbHeight = (frame.height * scale).toInt()

            val thumbnail = Bitmap.createScaledBitmap(frame, thumbWidth, thumbHeight, true)
            frame.recycle()

            val output = ByteArrayOutputStream()
            thumbnail.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, output)
            thumbnail.recycle()

            retriever.release()
            output.toByteArray()
        } catch (e: Exception) {
            Timber.w(e, "Video thumbnail generation failed")
            null
        }
    }

    /**
     * Gets image dimensions from data.
     */
    fun getImageDimensions(data: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            Pair(options.outWidth, options.outHeight)
        } else null
    }

    /**
     * Gets video duration in milliseconds.
     */
    fun getVideoDuration(uri: Uri): Long? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            retriever.release()
            duration
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val MAX_PHOTO_DIMENSION = 2048
        const val PHOTO_QUALITY = 85
        const val THUMBNAIL_SIZE = 200
        const val THUMBNAIL_QUALITY = 70
    }
}
