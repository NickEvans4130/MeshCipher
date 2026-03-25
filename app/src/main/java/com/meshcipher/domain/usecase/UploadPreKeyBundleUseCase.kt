package com.meshcipher.domain.usecase

import android.util.Base64
import com.meshcipher.data.encryption.PreKeyManager
import com.meshcipher.data.remote.api.RelayApiService
import com.meshcipher.data.remote.dto.UploadPreKeyBundleRequest
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

            val request = UploadPreKeyBundleRequest(
                registrationId = bundle.registrationId,
                preKeyId = bundle.preKeyId,
                preKey = Base64.encodeToString(preKeyBytes, Base64.NO_WRAP),
                signedPreKeyId = bundle.signedPreKeyId,
                signedPreKey = Base64.encodeToString(spkBytes, Base64.NO_WRAP),
                signedPreKeySignature = Base64.encodeToString(bundle.signedPreKeySignature, Base64.NO_WRAP),
                identityKey = Base64.encodeToString(bundle.identityKey.serialize(), Base64.NO_WRAP),
                // RM-10: include Kyber fields if available
                kyberPreKeyId = runCatching { bundle.kyberPreKeyId.takeIf { it > 0 } }.getOrNull(),
                kyberPreKey = runCatching {
                    bundle.kyberPreKey?.serialize()?.let {
                        Base64.encodeToString(it, Base64.NO_WRAP)
                    }
                }.getOrNull(),
                kyberPreKeySignature = runCatching {
                    bundle.kyberPreKeySignature?.let {
                        Base64.encodeToString(it, Base64.NO_WRAP)
                    }
                }.getOrNull()
            )

            val response = relayApiService.uploadPreKeyBundle(request)
            if (response.isSuccessful) {
                Timber.d("Pre-key bundle uploaded (PQXDH=%b)", request.kyberPreKeyId != null)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Upload failed: HTTP ${response.code()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to upload pre-key bundle")
            Result.failure(e)
        }
    }
}
