package com.meshcipher.data.identity

import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.domain.model.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityManager @Inject constructor(
    private val keyManager: KeyManager,
    private val identityStorage: IdentityStorage,
    private val appPreferences: AppPreferences
) {

    suspend fun createIdentity(deviceName: String): Identity = withContext(Dispatchers.IO) {
        val publicKey = keyManager.generateHardwareKey()
        // userId is a stable random ID, not derived from the Keystore key.
        // This means it survives reinstalls when Android Auto Backup restores AppPreferences,
        // even though the Keystore key is regenerated.
        val userId = UUID.randomUUID().toString().replace("-", "").take(32)

        val identity = Identity(
            userId = userId,
            hardwarePublicKey = publicKey.encoded,
            createdAt = System.currentTimeMillis(),
            deviceId = UUID.randomUUID().toString(),
            deviceName = deviceName,
            recoverySetup = false
        )

        identityStorage.saveIdentity(identity)
        identity
    }

    suspend fun getIdentity(): Identity? = withContext(Dispatchers.IO) {
        identityStorage.getIdentity() ?: recoverIdentity()
    }

    /**
     * Returns true if the user has an identity, either fully intact or recoverable
     * from AppPreferences (e.g. after a reinstall where Auto Backup restored the userId).
     */
    suspend fun hasIdentity(): Boolean = withContext(Dispatchers.IO) {
        !appPreferences.userId.firstOrNull().isNullOrBlank()
    }

    /**
     * If AppPreferences holds a userId from a prior install (restored by Auto Backup)
     * but EncryptedSharedPreferences was wiped, silently regenerate the Keystore key
     * and reconstruct the Identity record using the backed-up userId and display name.
     * Returns the recovered Identity, or null if no prior userId is known.
     */
    suspend fun recoverIdentity(): Identity? = withContext(Dispatchers.IO) {
        val userId = appPreferences.userId.firstOrNull()
        if (userId.isNullOrBlank()) return@withContext null

        return@withContext try {
            val publicKey = keyManager.generateHardwareKey()
            val displayName = appPreferences.displayName.firstOrNull() ?: "Restored Device"
            val identity = Identity(
                userId = userId,
                hardwarePublicKey = publicKey.encoded,
                createdAt = System.currentTimeMillis(),
                deviceId = UUID.randomUUID().toString(),
                deviceName = displayName,
                recoverySetup = false
            )
            identityStorage.saveIdentity(identity)
            Timber.d("Identity recovered from backup: %s", userId)
            identity
        } catch (e: Exception) {
            Timber.e(e, "Identity recovery failed")
            null
        }
    }

    suspend fun signChallenge(challenge: ByteArray): ByteArray {
        return withContext(Dispatchers.IO) {
            keyManager.signWithHardwareKey(challenge)
        }
    }

    suspend fun deleteIdentity() = withContext(Dispatchers.IO) {
        keyManager.deleteHardwareKey()
        identityStorage.deleteIdentity()
        appPreferences.setUserId("")
    }
}
