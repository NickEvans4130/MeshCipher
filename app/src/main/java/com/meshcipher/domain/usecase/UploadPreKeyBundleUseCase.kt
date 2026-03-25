package com.meshcipher.domain.usecase

import android.util.Base64
import com.meshcipher.data.encryption.PreKeyManager
import com.meshcipher.data.remote.api.RelayApiService
import com.meshcipher.data.remote.dto.UploadPreKeyBundleRequest
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject

/**
 * RM-10 / GAP-08 / R-09: Uploads the local PQXDH pre-key bundle to the relay so
 * that remote contacts can initiate PQXDH sessions.
 *
 * Called at startup (after auth) and when pre-keys are rotated.
 */
class UploadPreKeyBundleUseCase @Inject constructor(
    private val preKeyManager: PreKeyManager,
    private val relayApiService: RelayApiService
) {
    suspend operator fun invoke(): Result<Unit> {
        return try {
            val bundle = preKeyManager.buildLocalBundle()

            val preKeyBytes = bundle.preKey?.serialize()
                ?: return Result.failure(Exception("No pre-key available"))
            val spkBytes = bundle.signedPreKey?.serialize()
                ?: return Result.failure(Exception("No signed pre-key available"))

            // RM-10: Encode all three Kyber fields atomically — partial successes would
            // produce a malformed bundle that appears PQXDH-capable but isn't.
            val (kyberPreKeyId, kyberPreKey, kyberPreKeySignature) = runCatching {
                val kid = bundle.kyberPreKeyId.takeIf { it > 0 }
                val kpub = bundle.kyberPreKey?.serialize()
                    ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                val ksig = bundle.kyberPreKeySignature
                    ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                if (kid != null && kpub != null && ksig != null) Triple(kid, kpub, ksig)
                else Triple(null, null, null)
            }.getOrDefault(Triple(null, null, null))

            val request = UploadPreKeyBundleRequest(
                registrationId = bundle.registrationId,
                preKeyId = bundle.preKeyId,
                preKey = Base64.encodeToString(preKeyBytes, Base64.NO_WRAP),
                signedPreKeyId = bundle.signedPreKeyId,
                signedPreKey = Base64.encodeToString(spkBytes, Base64.NO_WRAP),
                signedPreKeySignature = Base64.encodeToString(bundle.signedPreKeySignature, Base64.NO_WRAP),
                identityKey = Base64.encodeToString(bundle.identityKey.serialize(), Base64.NO_WRAP),
                kyberPreKeyId = kyberPreKeyId,
                kyberPreKey = kyberPreKey,
                kyberPreKeySignature = kyberPreKeySignature
            )

            val response = relayApiService.uploadPreKeyBundle(request)
            if (response.isSuccessful) {
                Timber.d("Pre-key bundle uploaded (PQXDH=%b)", request.kyberPreKeyId != null)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Upload failed: HTTP ${response.code()}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to upload pre-key bundle")
            Result.failure(e)
        }
    }
}
