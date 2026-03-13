package com.meshcipher.desktop.network

import com.meshcipher.desktop.platform.DesktopPlatform
import com.meshcipher.shared.crypto.KeyManager
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

object RelayAuthManager {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Full registration + authentication flow against the relay server.
     *
     * Steps:
     * 1. Generate EC P-256 keys if not already present
     * 2. Derive userId = Base64Url(SHA-256(publicKey)).take(32)  — matches Android
     * 3. POST /api/v1/register  — idempotent, safe to call on every launch
     * 4. POST /api/v1/auth/challenge  — server issues a 32-byte random challenge
     * 5. Sign the challenge bytes with SHA256withECDSA
     * 6. POST /api/v1/auth/verify  — server verifies signature and returns JWT
     * 7. Persist relayUrl + authToken to relay.conf
     *
     * Returns the JWT token on success.
     */
    suspend fun authenticate(
        relayUrl: String,
        keyManager: KeyManager,
        deviceId: String
    ): Result<String> = runCatching {
        val base = relayUrl.trimEnd('/')

        // 1. Keys
        val publicKeyBytes = if (keyManager.hasHardwareKey()) {
            keyManager.getPublicKey()
        } else {
            keyManager.generateHardwareKey()
        }
        val publicKeyB64 = Base64.getEncoder().encodeToString(publicKeyBytes)

        // 2. Derive userId — must match Android IdentityManager
        val hash = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        val userId = Base64.getUrlEncoder().withoutPadding().encodeToString(hash).take(32)

        // 3. Register device (idempotent)
        val registerBody = buildJsonObject {
            put("device_id", deviceId)
            put("user_id", userId)
            put("public_key", publicKeyB64)
        }
        val regResp = client.post("$base/api/v1/register") {
            contentType(ContentType.Application.Json)
            setBody(registerBody.toString())
        }
        if (!regResp.status.isSuccess()) {
            throw Exception("Registration failed (${regResp.status.value}): ${regResp.bodyAsText()}")
        }

        // 4. Request challenge
        val challengeBody = buildJsonObject {
            put("userId", userId)
            put("publicKey", publicKeyB64)
        }
        val challengeResp = client.post("$base/api/v1/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(challengeBody.toString())
        }
        if (!challengeResp.status.isSuccess()) {
            throw Exception("Challenge request failed (${challengeResp.status.value}): ${challengeResp.bodyAsText()}")
        }
        val challengeB64 = json.parseToJsonElement(challengeResp.bodyAsText())
            .jsonObject["challenge"]?.jsonPrimitive?.content
            ?: throw Exception("No challenge field in server response")
        val challengeBytes = Base64.getDecoder().decode(challengeB64)

        // 5. Sign challenge
        val signatureBytes = keyManager.signWithHardwareKey(challengeBytes)
        val signatureB64 = Base64.getEncoder().encodeToString(signatureBytes)

        // 6. Verify
        val verifyBody = buildJsonObject {
            put("userId", userId)
            put("signature", signatureB64)
        }
        val verifyResp = client.post("$base/api/v1/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(verifyBody.toString())
        }
        if (!verifyResp.status.isSuccess()) {
            throw Exception("Auth failed (${verifyResp.status.value}): ${verifyResp.bodyAsText()}")
        }
        val token = json.parseToJsonElement(verifyResp.bodyAsText())
            .jsonObject["token"]?.jsonPrimitive?.content
            ?: throw Exception("No token field in server response")

        // 7. Persist
        saveConfig(relayUrl, token)

        token
    }

    fun hasValidConfig(): Boolean {
        val props = loadRawConfig()
        return !props.getProperty("relayUrl").isNullOrBlank() &&
               !props.getProperty("authToken").isNullOrBlank()
    }

    fun loadConfig(): Map<String, String> {
        val props = loadRawConfig()
        return props.entries.associate { (k, v) -> k.toString() to v.toString() }
    }

    private fun saveConfig(relayUrl: String, authToken: String) {
        val file = DesktopPlatform.configDir.resolve("relay.conf")
        val props = if (file.exists()) loadRawConfig() else Properties()
        props["relayUrl"] = relayUrl
        props["authToken"] = authToken
        file.outputStream().use { props.store(it, "MeshCipher relay configuration") }
    }

    private fun loadRawConfig(): Properties {
        val file = DesktopPlatform.configDir.resolve("relay.conf")
        val props = Properties()
        if (file.exists()) file.inputStream().use { props.load(it) }
        return props
    }
}
