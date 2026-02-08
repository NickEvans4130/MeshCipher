package com.meshcipher.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaProcessor @Inject constructor() {

    fun processImage(context: Context, uri: Uri): File {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI: $uri")

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream.close()

        val originalWidth = options.outWidth
        val originalHeight = options.outHeight
        var sampleSize = 1

        while (originalWidth / sampleSize > MAX_IMAGE_DIMENSION * 2 ||
            originalHeight / sampleSize > MAX_IMAGE_DIMENSION * 2) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        val decodeStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI: $uri")
        val bitmap = BitmapFactory.decodeStream(decodeStream, null, decodeOptions)
            ?: throw IllegalArgumentException("Cannot decode image from URI: $uri")
        decodeStream.close()

        val scaledBitmap = scaleBitmap(bitmap, MAX_IMAGE_DIMENSION)
        if (scaledBitmap !== bitmap) {
            bitmap.recycle()
        }

        val outputFile = File(context.cacheDir, "media_img_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outputFile).use { fos ->
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
        }
        scaledBitmap.recycle()

        Timber.d("Processed image: %dx%d -> %d bytes",
            originalWidth, originalHeight, outputFile.length())

        return outputFile
    }

    fun processVideo(context: Context, uri: Uri): File {
        val outputFile = File(context.cacheDir, "media_vid_${System.currentTimeMillis()}.mp4")

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Cannot open URI: $uri")

        if (outputFile.length() > MAX_VIDEO_SIZE) {
            outputFile.delete()
            throw IllegalArgumentException(
                "Video too large: ${outputFile.length() / (1024 * 1024)}MB (max ${MAX_VIDEO_SIZE / (1024 * 1024)}MB)"
            )
        }

        Timber.d("Processed video: %d bytes", outputFile.length())
        return outputFile
    }

    fun processVoice(file: File): File {
        // Voice is already recorded as AAC, pass through
        return file
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxDimension && height <= maxDimension) {
            return bitmap
        }

        val ratio = width.toFloat() / height.toFloat()
        val newWidth: Int
        val newHeight: Int

        if (width > height) {
            newWidth = maxDimension
            newHeight = (maxDimension / ratio).toInt()
        } else {
            newHeight = maxDimension
            newWidth = (maxDimension * ratio).toInt()
        }

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    companion object {
        private const val MAX_IMAGE_DIMENSION = 2048
        private const val JPEG_QUALITY = 90
        private const val MAX_VIDEO_SIZE = 15L * 1024 * 1024 // 15MB
    }
}
