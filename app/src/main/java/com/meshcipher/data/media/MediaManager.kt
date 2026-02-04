package com.meshcipher.data.media

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.meshcipher.data.ipfs.IpfsManager
import com.meshcipher.domain.model.EncryptedMediaChunk
import com.meshcipher.domain.model.MediaChunk
import com.meshcipher.domain.model.MediaMetadata
import com.meshcipher.domain.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the complete media send/receive flow.
 *
 * Send: Pick media -> Compress -> Chunk -> Encrypt -> Upload to IPFS -> Send CIDs
 * Receive: Get CIDs -> Download from IPFS -> Decrypt -> Verify -> Assemble -> Display
 */
@Singleton
class MediaManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ipfsManager: IpfsManager,
    private val encryptionService: MediaEncryptionService,
    private val compressor: MediaCompressor
) {
    private val _uploadProgress = MutableStateFlow(0f)
    val uploadProgress: StateFlow<Float> = _uploadProgress.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    /**
     * Prepares and uploads a photo, returning metadata.
     */
    suspend fun sendPhoto(uri: Uri): Result<MediaMetadata> {
        _uploadProgress.value = 0f

        val compressed = compressor.compressPhoto(uri)
        if (compressed.isFailure) return Result.failure(compressed.exceptionOrNull()!!)
        val data = compressed.getOrThrow()

        _uploadProgress.value = 0.1f

        // Generate thumbnail
        val thumbnail = compressor.generatePhotoThumbnail(data)
        _uploadProgress.value = 0.2f

        val dimensions = compressor.getImageDimensions(data)

        return uploadMedia(
            mediaId = UUID.randomUUID().toString(),
            data = data,
            mediaType = MediaType.PHOTO,
            fileName = "photo_${System.currentTimeMillis()}.jpg",
            mimeType = "image/jpeg",
            thumbnail = thumbnail,
            width = dimensions?.first,
            height = dimensions?.second,
            durationMs = null
        )
    }

    /**
     * Prepares and uploads a video, returning metadata.
     */
    suspend fun sendVideo(uri: Uri): Result<MediaMetadata> {
        _uploadProgress.value = 0f

        val videoData = compressor.processVideo(uri)
        if (videoData.isFailure) return Result.failure(videoData.exceptionOrNull()!!)
        val data = videoData.getOrThrow()

        _uploadProgress.value = 0.1f

        val thumbnail = compressor.generateVideoThumbnail(uri)
        _uploadProgress.value = 0.2f

        val duration = compressor.getVideoDuration(uri)

        return uploadMedia(
            mediaId = UUID.randomUUID().toString(),
            data = data,
            mediaType = MediaType.VIDEO,
            fileName = "video_${System.currentTimeMillis()}.mp4",
            mimeType = "video/mp4",
            thumbnail = thumbnail,
            width = null,
            height = null,
            durationMs = duration
        )
    }

    /**
     * Uploads a voice note, returning metadata.
     */
    suspend fun sendVoiceNote(file: File, durationMs: Long): Result<MediaMetadata> {
        _uploadProgress.value = 0f

        val data = withContext(Dispatchers.IO) { file.readBytes() }
        _uploadProgress.value = 0.1f

        return uploadMedia(
            mediaId = UUID.randomUUID().toString(),
            data = data,
            mediaType = MediaType.AUDIO,
            fileName = file.name,
            mimeType = "audio/m4a",
            thumbnail = null,
            width = null,
            height = null,
            durationMs = durationMs
        )
    }

    /**
     * Downloads and decrypts media from IPFS.
     */
    suspend fun downloadMedia(metadata: MediaMetadata): Result<ByteArray> = withContext(Dispatchers.IO) {
        _downloadProgress.value = 0f

        try {
            val output = ByteArrayOutputStream()
            val totalChunks = metadata.chunkCount

            for (i in 0 until totalChunks) {
                val cid = metadata.chunkCids[i]
                val keyBytes = MediaEncryptionService.decodeKey(metadata.chunkKeys[i])
                val nonceBytes = MediaEncryptionService.decodeKey(metadata.chunkNonces[i])
                val hash = metadata.chunkHashes[i]

                // Download encrypted chunk from IPFS
                val downloadResult = ipfsManager.download(cid)
                if (downloadResult.isFailure) {
                    return@withContext Result.failure(
                        Exception("Failed to download chunk $i: ${downloadResult.exceptionOrNull()?.message}")
                    )
                }

                val encryptedData = downloadResult.getOrThrow()

                // Decrypt
                val encrypted = EncryptedMediaChunk(
                    index = i,
                    encryptedData = encryptedData,
                    nonce = nonceBytes,
                    chunkKey = keyBytes,
                    originalHash = hash
                )

                val decryptedData = encryptionService.decryptChunk(encrypted)
                output.write(decryptedData)

                _downloadProgress.value = (i + 1).toFloat() / totalChunks
                Timber.d("Downloaded chunk %d/%d", i + 1, totalChunks)
            }

            Result.success(output.toByteArray())
        } catch (e: Exception) {
            Timber.e(e, "Failed to download media")
            Result.failure(e)
        }
    }

    /**
     * Downloads and decrypts a thumbnail.
     */
    suspend fun downloadThumbnail(metadata: MediaMetadata): ByteArray? {
        val cid = metadata.thumbnailCid ?: return null
        val key = metadata.thumbnailKey ?: return null
        val nonce = metadata.thumbnailNonce ?: return null

        return try {
            val downloadResult = ipfsManager.download(cid)
            if (downloadResult.isFailure) return null

            val encryptedData = downloadResult.getOrThrow()
            encryptionService.decrypt(
                encryptedData,
                MediaEncryptionService.decodeKey(key),
                MediaEncryptionService.decodeKey(nonce)
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to download thumbnail")
            null
        }
    }

    private suspend fun uploadMedia(
        mediaId: String,
        data: ByteArray,
        mediaType: MediaType,
        fileName: String,
        mimeType: String,
        thumbnail: ByteArray?,
        width: Int?,
        height: Int?,
        durationMs: Long?
    ): Result<MediaMetadata> = withContext(Dispatchers.IO) {
        try {
            // Chunk the data
            val chunks = MediaChunk.splitIntoChunks(data)
            val totalSteps = chunks.size + 1 // +1 for thumbnail
            var completedSteps = 0

            val chunkCids = mutableListOf<String>()
            val chunkHashes = mutableListOf<String>()
            val chunkKeys = mutableListOf<String>()
            val chunkNonces = mutableListOf<String>()

            // Encrypt and upload chunks (parallel batches of 3)
            coroutineScope {
                chunks.chunked(PARALLEL_UPLOADS).forEach { batch ->
                    val results = batch.map { chunk ->
                        async {
                            val encrypted = encryptionService.encryptChunk(chunk)
                            val uploadResult = ipfsManager.upload(encrypted.encryptedData)
                            if (uploadResult.isFailure) {
                                throw uploadResult.exceptionOrNull()!!
                            }
                            val cid = uploadResult.getOrThrow()
                            Triple(encrypted, cid, chunk.hash)
                        }
                    }.awaitAll()

                    results.forEach { (encrypted, cid, hash) ->
                        chunkCids.add(cid)
                        chunkHashes.add(hash)
                        chunkKeys.add(MediaEncryptionService.encodeKey(encrypted.chunkKey))
                        chunkNonces.add(MediaEncryptionService.encodeKey(encrypted.nonce))
                        completedSteps++
                        _uploadProgress.value = 0.2f + (completedSteps.toFloat() / totalSteps) * 0.8f
                    }
                }
            }

            // Upload thumbnail if present
            var thumbnailCid: String? = null
            var thumbnailKey: String? = null
            var thumbnailNonce: String? = null

            if (thumbnail != null) {
                val (encThumb, thumbKey, thumbNonce) = encryptionService.encrypt(thumbnail)
                val thumbUploadResult = ipfsManager.upload(encThumb)
                if (thumbUploadResult.isSuccess) {
                    thumbnailCid = thumbUploadResult.getOrThrow()
                    thumbnailKey = MediaEncryptionService.encodeKey(thumbKey)
                    thumbnailNonce = MediaEncryptionService.encodeKey(thumbNonce)
                }
            }

            _uploadProgress.value = 1f

            val metadata = MediaMetadata(
                id = mediaId,
                mediaType = mediaType,
                fileName = fileName,
                mimeType = mimeType,
                fileSize = data.size.toLong(),
                chunkCount = chunks.size,
                chunkCids = chunkCids,
                chunkHashes = chunkHashes,
                chunkKeys = chunkKeys,
                chunkNonces = chunkNonces,
                thumbnailCid = thumbnailCid,
                thumbnailKey = thumbnailKey,
                thumbnailNonce = thumbnailNonce,
                width = width,
                height = height,
                durationMs = durationMs
            )

            Timber.d("Media uploaded: %s, %d chunks, %d bytes",
                mediaType, chunks.size, data.size)

            Result.success(metadata)
        } catch (e: Exception) {
            Timber.e(e, "Failed to upload media")
            _uploadProgress.value = 0f
            Result.failure(e)
        }
    }

    /**
     * Gets the MIME type from a content URI.
     */
    fun getMimeType(uri: Uri): String {
        return context.contentResolver.getType(uri) ?: "application/octet-stream"
    }

    /**
     * Determines MediaType from a content URI.
     */
    fun getMediaType(uri: Uri): MediaType {
        return MediaType.fromMimeType(getMimeType(uri))
    }

    /**
     * Gets a local file for caching downloaded media.
     */
    fun getMediaCacheFile(mediaId: String, mediaType: MediaType): File {
        val dir = File(context.filesDir, "media_cache").also { it.mkdirs() }
        return File(dir, "$mediaId${mediaType.getFileExtension()}")
    }

    companion object {
        private const val PARALLEL_UPLOADS = 3
    }
}
