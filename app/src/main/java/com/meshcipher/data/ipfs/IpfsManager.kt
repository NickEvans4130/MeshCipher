package com.meshcipher.data.ipfs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages IPFS operations for decentralized media storage.
 *
 * Upload flow: raw bytes -> IPFS -> CID (content identifier)
 * Download flow: CID -> IPFS gateway -> raw bytes
 *
 * Falls back through gateways:
 * 1. Local IPFS node (fastest, most private)
 * 2. Public IPFS gateways
 */
@Singleton
class IpfsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val cacheDir: File
        get() = File(context.cacheDir, "ipfs_cache").also { it.mkdirs() }

    /**
     * Uploads data to IPFS and returns the CID.
     */
    suspend fun upload(data: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        // Try local node first
        val localResult = uploadToLocalNode(data)
        if (localResult.isSuccess) return@withContext localResult

        // Fall back to storing locally with content-addressed hash
        val cid = storeLocally(data)
        Result.success(cid)
    }

    /**
     * Downloads data from IPFS by CID.
     */
    suspend fun download(cid: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        // Check local cache first
        val cached = loadFromCache(cid)
        if (cached != null) return@withContext Result.success(cached)

        // Try local IPFS node
        val localResult = downloadFromLocalNode(cid)
        if (localResult.isSuccess) {
            cacheData(cid, localResult.getOrThrow())
            return@withContext localResult
        }

        // Try public gateways
        for (gateway in PUBLIC_GATEWAYS) {
            val result = downloadFromGateway(gateway, cid)
            if (result.isSuccess) {
                cacheData(cid, result.getOrThrow())
                return@withContext result
            }
        }

        // Check local storage
        val local = loadFromLocalStorage(cid)
        if (local != null) return@withContext Result.success(local)

        Result.failure(IOException("Failed to download from IPFS: $cid"))
    }

    /**
     * Checks if content is available (locally or on IPFS).
     */
    suspend fun isAvailable(cid: String): Boolean = withContext(Dispatchers.IO) {
        loadFromCache(cid) != null ||
            loadFromLocalStorage(cid) != null ||
            downloadFromLocalNode(cid).isSuccess
    }

    private fun uploadToLocalNode(data: ByteArray): Result<String> {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", "chunk",
                    data.toRequestBody("application/octet-stream".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("$LOCAL_API/api/v0/add?pin=true")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "")
                val cid = json.getString("Hash")
                Timber.d("Uploaded to local IPFS node: %s (%d bytes)", cid, data.size)
                Result.success(cid)
            } else {
                Result.failure(IOException("IPFS upload failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Timber.d("Local IPFS node not available: %s", e.message)
            Result.failure(e)
        }
    }

    private fun downloadFromLocalNode(cid: String): Result<ByteArray> {
        return try {
            val request = Request.Builder()
                .url("$LOCAL_API/api/v0/cat?arg=$cid")
                .post("".toRequestBody())
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val data = response.body?.bytes() ?: return Result.failure(IOException("Empty response"))
                Timber.d("Downloaded from local IPFS: %s (%d bytes)", cid, data.size)
                Result.success(data)
            } else {
                Result.failure(IOException("IPFS download failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun downloadFromGateway(gateway: String, cid: String): Result<ByteArray> {
        return try {
            val request = Request.Builder()
                .url("$gateway/ipfs/$cid")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val data = response.body?.bytes() ?: return Result.failure(IOException("Empty response"))
                Timber.d("Downloaded from gateway %s: %s (%d bytes)", gateway, cid, data.size)
                Result.success(data)
            } else {
                Result.failure(IOException("Gateway download failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Stores data locally with content-addressed naming (fallback when IPFS not available).
     */
    private fun storeLocally(data: ByteArray): String {
        val cid = "local-${com.meshcipher.domain.model.MediaChunk.computeHash(data)}"
        val file = File(getLocalStorageDir(), cid)
        file.writeBytes(data)
        Timber.d("Stored locally: %s (%d bytes)", cid, data.size)
        return cid
    }

    private fun loadFromLocalStorage(cid: String): ByteArray? {
        val file = File(getLocalStorageDir(), cid)
        return if (file.exists()) file.readBytes() else null
    }

    private fun getLocalStorageDir(): File {
        return File(context.filesDir, "ipfs_local").also { it.mkdirs() }
    }

    private fun cacheData(cid: String, data: ByteArray) {
        try {
            File(cacheDir, cid).writeBytes(data)
        } catch (e: Exception) {
            Timber.w(e, "Failed to cache IPFS data")
        }
    }

    private fun loadFromCache(cid: String): ByteArray? {
        val file = File(cacheDir, cid)
        return if (file.exists()) file.readBytes() else null
    }

    /**
     * Cleans up old cached data.
     */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val LOCAL_API = "http://127.0.0.1:5001"

        private val PUBLIC_GATEWAYS = listOf(
            "https://dweb.link",
            "https://ipfs.io",
            "https://cloudflare-ipfs.com"
        )
    }
}
