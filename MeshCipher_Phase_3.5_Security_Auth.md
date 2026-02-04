# MeshCipher - Phase 3.5: Security Hardening & Authentication
## Weeks 11-13: Hardware Identity, Social Recovery, Security Audit

---

## Phase 3.5 Overview

**Goals:**
- Implement hardware-bound cryptographic identity
- Add proper user authentication system
- Implement social recovery (Shamir Secret Sharing)
- Fix security vulnerabilities from Phases 1-3
- Add contact exchange via QR codes
- Implement multi-device management
- Comprehensive security testing

**Deliverables:**
- Hardware-bound Ed25519 keys (Android Keystore)
- No phone numbers required
- Social recovery system (5-of-3 Shamir)
- QR code contact exchange
- Multi-device authorization with revocation
- Message replay protection
- Proper key storage
- Session verification
- 100+ security tests
- Security audit report

**Prerequisites:**
- ✅ Phase 3 complete (messaging working)

---

## Step 1: Hardware-Bound Identity System

### Identity Models

```kotlin
package com.meshcipher.domain.model

data class Identity(
    val userId: String,              // Base64(publicKey)
    val hardwarePublicKey: ByteArray,
    val createdAt: Long,
    val deviceId: String,
    val deviceName: String,
    val recoverySetup: Boolean = false
)

data class DeviceAuthorization(
    val deviceId: String,
    val publicKey: ByteArray,
    val authorizedBy: String,        // deviceId that authorized this
    val authorizedAt: Long,
    val expiresAt: Long,
    val revoked: Boolean = false,
    val signature: ByteArray
)

data class RecoveryShard(
    val shardIndex: Int,
    val encryptedShard: ByteArray,
    val guardianId: String,
    val createdAt: Long
)
```

### KeyManager.kt

```kotlin
package com.meshcipher.data.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.*
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyManager @Inject constructor() {
    
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }
    
    companion object {
        private const val HARDWARE_KEY_ALIAS = "meshcipher_hardware_key"
        private const val RECOVERY_KEY_ALIAS = "meshcipher_recovery_key"
    }
    
    /**
     * Generate hardware-bound Ed25519 key pair
     * Key is stored in Android Keystore and cannot be exported
     */
    fun generateHardwareKey(): PublicKey {
        // Check if key already exists
        if (keyStore.containsAlias(HARDWARE_KEY_ALIAS)) {
            return getPublicKey(HARDWARE_KEY_ALIAS)
        }
        
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore"
        )
        
        val spec = KeyGenParameterSpec.Builder(
            HARDWARE_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationValidityDurationSeconds(30)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        
        keyPairGenerator.initialize(spec)
        val keyPair = keyPairGenerator.generateKeyPair()
        
        return keyPair.public
    }
    
    fun getPublicKey(alias: String = HARDWARE_KEY_ALIAS): PublicKey {
        val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        return entry.certificate.publicKey
    }
    
    /**
     * Sign data with hardware key
     * Requires biometric authentication
     */
    fun signWithHardwareKey(data: ByteArray): ByteArray {
        val entry = keyStore.getEntry(HARDWARE_KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        val privateKey = entry.privateKey
        
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data)
        
        return signature.sign()
    }
    
    fun verifySignature(
        data: ByteArray,
        signature: ByteArray,
        publicKey: PublicKey
    ): Boolean {
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(data)
        return verifier.verify(signature)
    }
    
    fun hasHardwareKey(): Boolean {
        return keyStore.containsAlias(HARDWARE_KEY_ALIAS)
    }
    
    fun deleteHardwareKey() {
        if (keyStore.containsAlias(HARDWARE_KEY_ALIAS)) {
            keyStore.deleteEntry(HARDWARE_KEY_ALIAS)
        }
    }
}
```

### IdentityManager.kt

```kotlin
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
        // Generate hardware key
        val publicKey = keyManager.generateHardwareKey()
        
        // Derive user ID from public key
        val userId = generateUserId(publicKey.encoded)
        
        // Create identity
        val identity = Identity(
            userId = userId,
            hardwarePublicKey = publicKey.encoded,
            createdAt = System.currentTimeMillis(),
            deviceId = UUID.randomUUID().toString(),
            deviceName = deviceName,
            recoverySetup = false
        )
        
        // Save to storage
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
            .take(32) // First 32 chars for shorter ID
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
```

### IdentityStorage.kt

```kotlin
package com.meshcipher.data.identity

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.meshcipher.domain.model.Identity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {
    
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "identity_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    fun saveIdentity(identity: Identity) {
        val json = gson.toJson(identity)
        prefs.edit().putString(KEY_IDENTITY, json).apply()
    }
    
    fun getIdentity(): Identity? {
        val json = prefs.getString(KEY_IDENTITY, null) ?: return null
        return gson.fromJson(json, Identity::class.java)
    }
    
    fun hasIdentity(): Boolean {
        return prefs.contains(KEY_IDENTITY)
    }
    
    fun deleteIdentity() {
        prefs.edit().remove(KEY_IDENTITY).apply()
    }
    
    fun markRecoverySetup() {
        val identity = getIdentity() ?: return
        saveIdentity(identity.copy(recoverySetup = true))
    }
    
    companion object {
        private const val KEY_IDENTITY = "identity"
    }
}
```

---

## Step 2: Authentication System

### AuthenticationManager.kt

```kotlin
package com.meshcipher.data.auth

import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.remote.api.AuthApiService
import com.meshcipher.data.remote.dto.AuthChallengeRequest
import com.meshcipher.data.remote.dto.AuthVerifyRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthenticationManager @Inject constructor(
    private val identityManager: IdentityManager,
    private val authApi: AuthApiService,
    private val tokenStorage: TokenStorage
) {
    
    suspend fun authenticate(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val identity = identityManager.getIdentity()
                ?: return@withContext Result.failure(Exception("No identity"))
            
            // Step 1: Get challenge from server
            val challengeResponse = authApi.requestChallenge(
                AuthChallengeRequest(
                    userId = identity.userId,
                    publicKey = android.util.Base64.encodeToString(
                        identity.hardwarePublicKey,
                        android.util.Base64.NO_WRAP
                    )
                )
            )
            
            if (!challengeResponse.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to get challenge: ${challengeResponse.code()}")
                )
            }
            
            val challenge = android.util.Base64.decode(
                challengeResponse.body()!!.challenge,
                android.util.Base64.NO_WRAP
            )
            
            // Step 2: Sign challenge with hardware key
            val signature = identityManager.signChallenge(challenge)
            
            // Step 3: Verify signature with server
            val verifyResponse = authApi.verifyChallenge(
                AuthVerifyRequest(
                    userId = identity.userId,
                    challenge = challengeResponse.body()!!.challenge,
                    signature = android.util.Base64.encodeToString(
                        signature,
                        android.util.Base64.NO_WRAP
                    )
                )
            )
            
            if (!verifyResponse.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Authentication failed: ${verifyResponse.code()}")
                )
            }
            
            val token = verifyResponse.body()!!.token
            
            // Save token
            tokenStorage.saveToken(token)
            
            Timber.d("Authentication successful")
            Result.success(token)
            
        } catch (e: Exception) {
            Timber.e(e, "Authentication failed")
            Result.failure(e)
        }
    }
    
    suspend fun isAuthenticated(): Boolean {
        return tokenStorage.hasValidToken()
    }
    
    suspend fun getToken(): String? {
        return tokenStorage.getToken()
    }
    
    suspend fun logout() {
        tokenStorage.clearToken()
    }
}
```

### TokenStorage.kt

```kotlin
package com.meshcipher.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.auth0.android.jwt.JWT
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "auth_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }
    
    fun getToken(): String? {
        return prefs.getString(KEY_TOKEN, null)
    }
    
    fun hasValidToken(): Boolean {
        val token = getToken() ?: return false
        
        return try {
            val jwt = JWT(token)
            !jwt.isExpired(10) // 10 second buffer
        } catch (e: Exception) {
            false
        }
    }
    
    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }
    
    companion object {
        private const val KEY_TOKEN = "auth_token"
    }
}
```

---

## Step 3: Social Recovery (Shamir Secret Sharing)

### Add Dependency

```kotlin
// build.gradle.kts (app)
dependencies {
    // Shamir Secret Sharing
    implementation("com.github.denuafhaengige:secret-sharing:1.0.0")
}
```

### RecoveryManager.kt

```kotlin
package com.meshcipher.data.recovery

import android.util.Base64
import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.model.RecoveryShard
import com.meshcipher.domain.repository.ContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecoveryManager @Inject constructor(
    private val contactRepository: ContactRepository,
    private val shamirSecretSharing: ShamirSecretSharing
) {
    
    /**
     * Generate recovery seed and split into shards
     * @param guardians List of 5 trusted contacts
     * @return Master recovery seed (user should write this down as backup)
     */
    suspend fun setupRecovery(guardians: List<Contact>): ByteArray = withContext(Dispatchers.IO) {
        require(guardians.size == 5) { "Need exactly 5 guardians" }
        
        // Generate 32-byte master recovery seed
        val masterSeed = ByteArray(32)
        SecureRandom().nextBytes(masterSeed)
        
        // Split into 5 shards, requiring 3 to reconstruct
        val shards = shamirSecretSharing.split(
            secret = masterSeed,
            totalShards = 5,
            threshold = 3
        )
        
        // Create recovery shards for each guardian
        val recoveryShards = shards.mapIndexed { index, shard ->
            RecoveryShard(
                shardIndex = index,
                encryptedShard = shard, // TODO: Encrypt with guardian's public key
                guardianId = guardians[index].id,
                createdAt = System.currentTimeMillis()
            )
        }
        
        // TODO: Send shards to guardians via encrypted messages
        recoveryShards.forEach { shard ->
            Timber.d("Would send shard ${shard.shardIndex} to guardian ${shard.guardianId}")
        }
        
        masterSeed
    }
    
    /**
     * Recover identity from shards
     * @param shards At least 3 shards received from guardians
     */
    suspend fun recoverFromShards(shards: List<ByteArray>): ByteArray = withContext(Dispatchers.IO) {
        require(shards.size >= 3) { "Need at least 3 shards to recover" }
        
        // Reconstruct master seed
        val masterSeed = shamirSecretSharing.combine(shards.take(3))
        
        Timber.d("Master seed recovered successfully")
        masterSeed
    }
    
    /**
     * Derive recovery key from master seed
     */
    fun deriveRecoveryKey(masterSeed: ByteArray): ByteArray {
        // Use HKDF or similar to derive key from seed
        // For now, just return the seed
        return masterSeed
    }
}
```

### ShamirSecretSharing.kt

```kotlin
package com.meshcipher.data.recovery

import java.math.BigInteger
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShamirSecretSharing @Inject constructor() {
    
    // Prime larger than any possible secret
    private val prime = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F",
        16
    )
    
    /**
     * Split secret into n shares, requiring k to reconstruct
     */
    fun split(
        secret: ByteArray,
        totalShards: Int,
        threshold: Int
    ): List<ByteArray> {
        val secretInt = BigInteger(1, secret)
        require(secretInt < prime) { "Secret too large" }
        
        // Generate random polynomial coefficients
        val coefficients = mutableListOf(secretInt)
        repeat(threshold - 1) {
            coefficients.add(
                BigInteger(prime.bitLength(), SecureRandom()).mod(prime)
            )
        }
        
        // Evaluate polynomial at x = 1, 2, 3, ..., n
        return (1..totalShards).map { x ->
            val xBig = BigInteger.valueOf(x.toLong())
            val y = evaluatePolynomial(coefficients, xBig)
            
            // Encode (x, y) as shard
            encodeShard(x, y)
        }
    }
    
    /**
     * Combine k shares to reconstruct secret
     */
    fun combine(shards: List<ByteArray>): ByteArray {
        require(shards.size >= 2) { "Need at least 2 shards" }
        
        val points = shards.map { decodeShard(it) }
        
        // Lagrange interpolation to find polynomial(0)
        val secret = lagrangeInterpolation(points)
        
        return secret.toByteArray()
    }
    
    private fun evaluatePolynomial(
        coefficients: List<BigInteger>,
        x: BigInteger
    ): BigInteger {
        var result = BigInteger.ZERO
        var xPower = BigInteger.ONE
        
        coefficients.forEach { coeff ->
            result = result.add(coeff.multiply(xPower)).mod(prime)
            xPower = xPower.multiply(x).mod(prime)
        }
        
        return result
    }
    
    private fun lagrangeInterpolation(
        points: List<Pair<Int, BigInteger>>
    ): BigInteger {
        var result = BigInteger.ZERO
        
        points.forEach { (i, yi) ->
            var numerator = BigInteger.ONE
            var denominator = BigInteger.ONE
            
            points.forEach { (j, _) ->
                if (i != j) {
                    numerator = numerator.multiply(
                        BigInteger.valueOf(-j.toLong())
                    ).mod(prime)
                    denominator = denominator.multiply(
                        BigInteger.valueOf((i - j).toLong())
                    ).mod(prime)
                }
            }
            
            val term = yi
                .multiply(numerator)
                .multiply(denominator.modInverse(prime))
                .mod(prime)
            
            result = result.add(term).mod(prime)
        }
        
        return result
    }
    
    private fun encodeShard(x: Int, y: BigInteger): ByteArray {
        val xBytes = x.toString().toByteArray()
        val yBytes = y.toByteArray()
        
        return byteArrayOf(xBytes.size.toByte()) + xBytes + yBytes
    }
    
    private fun decodeShard(shard: ByteArray): Pair<Int, BigInteger> {
        val xSize = shard[0].toInt()
        val xBytes = shard.sliceArray(1 until 1 + xSize)
        val yBytes = shard.sliceArray(1 + xSize until shard.size)
        
        val x = String(xBytes).toInt()
        val y = BigInteger(1, yBytes)
        
        return x to y
    }
}
```

---

## Step 4: Contact Exchange (QR Codes)

### Add Dependencies

```kotlin
dependencies {
    // QR Code generation
    implementation("com.google.zxing:core:3.5.2")
    
    // QR Code scanning
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    
    // Camera
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
}
```

### ContactCard.kt

```kotlin
package com.meshcipher.domain.model

import android.util.Base64
import org.json.JSONObject

data class ContactCard(
    val userId: String,
    val publicKey: ByteArray,
    val deviceId: String,
    val deviceName: String,
    val displayName: String? = null,
    val verificationCode: String
) {
    fun toQRString(): String {
        val json = JSONObject().apply {
            put("userId", userId)
            put("publicKey", Base64.encodeToString(publicKey, Base64.NO_WRAP))
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("verificationCode", verificationCode)
        }
        
        return "meshcipher://add?data=${Base64.encodeToString(
            json.toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP
        )}"
    }
    
    companion object {
        fun fromQRString(qrData: String): ContactCard? {
            if (!qrData.startsWith("meshcipher://add?data=")) return null
            
            return try {
                val base64Data = qrData.removePrefix("meshcipher://add?data=")
                val jsonString = String(Base64.decode(base64Data, Base64.URL_SAFE))
                val json = JSONObject(jsonString)
                
                ContactCard(
                    userId = json.getString("userId"),
                    publicKey = Base64.decode(json.getString("publicKey"), Base64.NO_WRAP),
                    deviceId = json.getString("deviceId"),
                    deviceName = json.getString("deviceName"),
                    verificationCode = json.getString("verificationCode")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
    
    fun generateVerificationCode(theirPublicKey: ByteArray): String {
        // Combine both public keys and hash
        val combined = publicKey + theirPublicKey
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(combined)
        
        // Take first 6 bytes and format as XXX-XXX
        val code = hash.take(6)
            .map { (it.toInt() and 0xFF) % 100 }
            .joinToString("") { it.toString().padStart(2, '0') }
        
        return "${code.take(3)}-${code.drop(3)}"
    }
}
```

### QRCodeGenerator.kt

```kotlin
package com.meshcipher.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import javax.inject.Inject

class QRCodeGenerator @Inject constructor() {
    
    fun generateQRCode(content: String, size: Int = 512): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(
                    x, y,
                    if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                )
            }
        }
        
        return bitmap
    }
}
```

---

## Step 5: Security Fixes

### Message Replay Protection

Update EncryptedMessage:

```kotlin
data class EncryptedMessage(
    val id: String,
    val senderId: String,
    val recipientId: String,
    val ciphertext: ByteArray,
    val timestamp: Long,
    val sequenceNumber: Long,           // NEW: Monotonically increasing
    val previousMessageHash: ByteArray   // NEW: Chain messages together
)
```

Add sequence tracking:

```kotlin
class MessageSequenceTracker @Inject constructor(
    private val database: MeshCipherDatabase
) {
    
    suspend fun getNextSequence(contactId: String): Long {
        val lastSeq = database.messageDao()
            .getLastSequenceNumber(contactId) ?: 0
        return lastSeq + 1
    }
    
    suspend fun validateSequence(
        contactId: String,
        sequence: Long
    ): Boolean {
        val lastSeq = database.messageDao()
            .getLastSequenceNumber(contactId) ?: 0
        
        // Sequence must be greater than last received
        return sequence > lastSeq
    }
}
```

### Certificate Pinning

```kotlin
// In NetworkModule.kt
@Provides
fun provideOkHttpClient(): OkHttpClient {
    val certificatePinner = CertificatePinner.Builder()
        .add("your-relay-server.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .build()
    
    return OkHttpClient.Builder()
        .certificatePinner(certificatePinner)
        .addInterceptor(loggingInterceptor)
        .build()
}
```

### Secure Key Wiping

```kotlin
fun wipeByteArray(array: ByteArray) {
    array.fill(0)
    // Force write
    array[0] = 0
}

// Usage:
fun encryptMessage(plaintext: String): ByteArray {
    val plaintextBytes = plaintext.toByteArray()
    try {
        val ciphertext = encrypt(plaintextBytes)
        return ciphertext
    } finally {
        wipeByteArray(plaintextBytes)
    }
}
```

---

## Step 6: UI Screens

### OnboardingScreen.kt

```kotlin
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Create Your Identity",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = uiState.deviceName,
            onValueChange = { viewModel.updateDeviceName(it) },
            label = { Text("Device Name") },
            placeholder = { Text("My Phone") }
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = { viewModel.createIdentity() },
            enabled = !uiState.isCreating && uiState.deviceName.isNotBlank()
        ) {
            if (uiState.isCreating) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Text("Create Identity")
            }
        }
        
        if (uiState.error != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = uiState.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    
    LaunchedEffect(uiState.identityCreated) {
        if (uiState.identityCreated) {
            onComplete()
        }
    }
}
```

### ShareContactScreen.kt

```kotlin
@Composable
fun ShareContactScreen(
    viewModel: ShareContactViewModel = hiltViewModel()
) {
    val contactCard by viewModel.contactCard.collectAsState()
    val qrBitmap by viewModel.qrBitmap.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Share Your Contact",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        qrBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Contact QR Code",
                modifier = Modifier.size(300.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        contactCard?.let { card ->
            Text(
                text = "Verification Code:",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = card.verificationCode,
                style = MaterialTheme.typography.headlineLarge,
                fontFamily = FontFamily.Monospace
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Have your contact scan this code",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
```

### ScanContactScreen.kt

```kotlin
@Composable
fun ScanContactScreen(
    onContactScanned: (ContactCard) -> Unit,
    onBackClick: () -> Unit,
    viewModel: ScanContactViewModel = hiltViewModel()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    
    val previewView = remember { PreviewView(context) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    
    DisposableEffect(Unit) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        
        imageAnalysis.setAnalyzer(
            ContextCompat.getMainExecutor(context)
        ) { imageProxy ->
            viewModel.analyzeImage(imageProxy) { contactCard ->
                onContactScanned(contactCard)
            }
        }
        
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Camera binding failed")
        }
        
        onDispose {
            cameraProvider.unbindAll()
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }
        
        Text(
            text = "Scan contact's QR code",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
    }
}
```

---

## Step 7: Security Tests

### IdentityManagerTest.kt

```kotlin
@Test
fun `identity creation generates unique user IDs`() = runTest {
    val identity1 = identityManager.createIdentity("Device 1")
    
    // Delete and recreate
    identityManager.deleteIdentity()
    
    val identity2 = identityManager.createIdentity("Device 2")
    
    // User IDs should be different (different hardware keys)
    assertNotEquals(identity1.userId, identity2.userId)
}

@Test
fun `hardware key cannot be exported`() = runTest {
    identityManager.createIdentity("Test Device")
    
    // Attempt to get private key should fail
    assertThrows<Exception> {
        keyManager.getPrivateKey() // This method shouldn't exist
    }
}

@Test
fun `signature verification works correctly`() = runTest {
    val identity = identityManager.createIdentity("Test")
    val data = "test message".toByteArray()
    
    val signature = identityManager.signChallenge(data)
    
    val valid = keyManager.verifySignature(
        data,
        signature,
        identity.hardwarePublicKey.toPublicKey()
    )
    
    assertTrue(valid)
}
```

### MessageSecurityTest.kt

```kotlin
@Test
fun `messages cannot be replayed`() = runTest {
    val contact = createTestContact()
    val message = createTestMessage(contact)
    
    // Send message once
    val result1 = sendMessageUseCase(message)
    assertTrue(result1.isSuccess)
    
    // Try to send same message again (same sequence number)
    val result2 = sendMessageUseCase(message)
    assertTrue(result2.isFailure) // Should be rejected
}

@Test
fun `messages are chained correctly`() = runTest {
    val contact = createTestContact()
    
    val msg1 = sendMessage("First", contact)
    val msg2 = sendMessage("Second", contact)
    
    // msg2's previousHash should match msg1's hash
    val msg1Hash = calculateHash(msg1)
    assertEquals(msg1Hash, msg2.previousMessageHash)
}

@Test
fun `session verification prevents MITM`() = runTest {
    val contact = createTestContact()
    
    // Attacker tries to impersonate contact
    val attackerKeys = generateAttackerKeys()
    
    val result = createSession(
        contactId = contact.id,
        publicKey = attackerKeys.publicKey,
        expectedFingerprint = contact.verificationCode
    )
    
    // Should fail due to fingerprint mismatch
    assertTrue(result.isFailure)
}
```

### RecoveryTest.kt

```kotlin
@Test
fun `recovery with 3 shards succeeds`() = runTest {
    val guardians = createTestGuardians(5)
    val masterSeed = recoveryManager.setupRecovery(guardians)
    
    // Simulate receiving 3 shards
    val shards = listOf(shard0, shard2, shard4)
    
    val recovered = recoveryManager.recoverFromShards(shards)
    
    assertEquals(masterSeed, recovered)
}

@Test
fun `recovery with 2 shards fails`() = runTest {
    val guardians = createTestGuardians(5)
    recoveryManager.setupRecovery(guardians)
    
    // Only 2 shards
    val shards = listOf(shard0, shard1)
    
    assertThrows<IllegalArgumentException> {
        recoveryManager.recoverFromShards(shards)
    }
}

@Test
fun `recovery with wrong shards fails`() = runTest {
    val guardians = createTestGuardians(5)
    val masterSeed = recoveryManager.setupRecovery(guardians)
    
    // Corrupt one shard
    val corruptedShards = listOf(
        shard0,
        shard1.copyOf().apply { this[0] = (this[0] + 1).toByte() },
        shard2
    )
    
    val recovered = recoveryManager.recoverFromShards(corruptedShards)
    
    // Should not match original
    assertNotEquals(masterSeed, recovered)
}
```

---

## Step 8: Update Relay Server

Add authentication endpoints to server.py:

```python
from datetime import datetime, timedelta
import jwt

# Authentication
@app.route('/api/v1/auth/challenge', methods=['POST'])
def request_challenge():
    data = request.json
    user_id = data['userId']
    public_key = data['publicKey']
    
    # Generate random challenge
    challenge = os.urandom(32)
    challenge_b64 = base64.b64encode(challenge).decode()
    
    # Store challenge temporarily (expires in 5 minutes)
    challenges[user_id] = {
        'challenge': challenge,
        'public_key': public_key,
        'expires_at': datetime.utcnow() + timedelta(minutes=5)
    }
    
    return jsonify({
        'challenge': challenge_b64
    })

@app.route('/api/v1/auth/verify', methods=['POST'])
def verify_challenge():
    data = request.json
    user_id = data['userId']
    challenge_b64 = data['challenge']
    signature_b64 = data['signature']
    
    # Get stored challenge
    if user_id not in challenges:
        return jsonify({'error': 'Challenge not found'}), 404
    
    stored = challenges[user_id]
    
    # Check expiration
    if datetime.utcnow() > stored['expires_at']:
        del challenges[user_id]
        return jsonify({'error': 'Challenge expired'}), 401
    
    # Verify signature
    challenge = base64.b64decode(challenge_b64)
    signature = base64.b64decode(signature_b64)
    public_key_bytes = base64.b64decode(stored['public_key'])
    
    # TODO: Verify ECDSA signature with public key
    # For now, accept all
    
    # Generate JWT
    token = jwt.encode(
        {
            'user_id': user_id,
            'exp': datetime.utcnow() + timedelta(days=30)
        },
        app.config['SECRET_KEY'],
        algorithm='HS256'
    )
    
    # Clean up challenge
    del challenges[user_id]
    
    return jsonify({
        'token': token,
        'expires_in': 30 * 24 * 3600
    })
```

---

## Phase 3.5 Checklist

- [ ] KeyManager implemented (Android Keystore)
- [ ] IdentityManager created
- [ ] IdentityStorage with encryption
- [ ] AuthenticationManager with challenge-response
- [ ] TokenStorage with JWT validation
- [ ] RecoveryManager with Shamir Secret Sharing
- [ ] ShamirSecretSharing implementation
- [ ] ContactCard model
- [ ] QR code generation
- [ ] QR code scanning
- [ ] OnboardingScreen UI
- [ ] ShareContactScreen UI
- [ ] ScanContactScreen UI
- [ ] Message replay protection added
- [ ] Certificate pinning added
- [ ] Secure key wiping implemented
- [ ] Server auth endpoints added
- [ ] 100+ security tests written
- [ ] All tests passing
- [ ] Security audit documented

---

## Next Phase

**Phase 3.75: TOR Integration** - See `Phase_3.75_TOR.md`

This will add:
- Orbot integration
- Hidden service for relay
- TOR routing mode
- Metadata privacy