package com.meshcipher.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
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

        // Read EXIF orientation and rotate if needed
        val rotatedBitmap = correctOrientation(context, uri, bitmap)
        if (rotatedBitmap !== bitmap) {
            bitmap.recycle()
        }

        val scaledBitmap = scaleBitmap(rotatedBitmap, MAX_IMAGE_DIMENSION)
        if (scaledBitmap !== rotatedBitmap) {
            rotatedBitmap.recycle()
        }

        val outputFile = File(context.cacheDir, "media_img_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outputFile).use { fos ->
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
        }
        scaledBitmap.recycle()

        stripExifMetadata(outputFile)

        Timber.d("Processed image: %dx%d -> %d bytes (EXIF stripped)",
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

    private fun stripExifMetadata(file: File) {
        try {
            val exif = ExifInterface(file)
            for (tag in EXIF_TAGS_TO_STRIP) {
                exif.setAttribute(tag, null)
            }
            exif.saveAttributes()
            Timber.d("Stripped EXIF metadata from %s", file.name)
        } catch (e: Exception) {
            Timber.w(e, "Failed to strip EXIF metadata")
        }
    }

    private fun correctOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(inputStream)
            inputStream.close()

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.preScale(-1f, 1f)
                }
                else -> return bitmap
            }

            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            rotated
        } catch (e: Exception) {
            Timber.w(e, "Failed to read EXIF orientation")
            bitmap
        }
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

        /** EXIF tags that can identify the sender, device, or location. */
        private val EXIF_TAGS_TO_STRIP = arrayOf(
            // GPS / Location
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            // Date / Time
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            // Device / Software
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_IMAGE_UNIQUE_ID,
            ExifInterface.TAG_CAMERA_OWNER_NAME,
            ExifInterface.TAG_BODY_SERIAL_NUMBER,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_LENS_SERIAL_NUMBER,
            // Author / Copyright
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            // Thumbnail (can contain its own EXIF with location)
            ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
            ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
        )
    }
}
