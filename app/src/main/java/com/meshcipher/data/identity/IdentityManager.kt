package com.meshcipher.data.identity

import android.util.Base64
import com.meshcipher.domain.model.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityManager @Inject constructor(
    private val keyManager: KeyManager,
    private val identityStorage: IdentityStorage
) {

    suspend fun createIdentity(deviceName: String): Identity = withContext(Dispatchers.IO) {
        val publicKey = keyManager.generateHardwareKey()
        val userId = generateUserId(publicKey.encoded)

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
        identityStorage.getIdentity()
    }

    suspend fun hasIdentity(): Boolean = withContext(Dispatchers.IO) {
        identityStorage.hasIdentity() && keyManager.hasHardwareKey()
    }

    private fun generateUserId(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return Base64.encodeToString(hash, Base64.NO_WRAP or Base64.URL_SAFE)
            .take(32)
    }

    suspend fun signChallenge(challenge: ByteArray): ByteArray {
        return withContext(Dispatchers.IO) {
            keyManager.signWithHardwareKey(challenge)
        }
    }

    suspend fun deleteIdentity() = withContext(Dispatchers.IO) {
        keyManager.deleteHardwareKey()
        identityStorage.deleteIdentity()
    }
}
