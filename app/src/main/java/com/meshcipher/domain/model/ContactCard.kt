package com.meshcipher.domain.model

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest

data class ContactCard(
    val userId: String,
    val publicKey: ByteArray,
    val deviceId: String,
    val deviceName: String,
    val displayName: String? = null,
    val verificationCode: String,
    val onionAddress: String? = null
) {
    fun toQRString(): String {
        val json = JSONObject().apply {
            put("userId", userId)
            put("publicKey", Base64.encodeToString(publicKey, Base64.NO_WRAP))
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("verificationCode", verificationCode)
            if (onionAddress != null) {
                put("onionAddress", onionAddress)
            }
        }

        return "meshcipher://add?data=${Base64.encodeToString(
            json.toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP
        )}"
    }

    fun generateVerificationCode(theirPublicKey: ByteArray): String {
        val combined = publicKey + theirPublicKey
        val hash = MessageDigest.getInstance("SHA-256").digest(combined)
        val code = hash.take(6)
            .map { (it.toInt() and 0xFF) % 100 }
            .joinToString("") { it.toString().padStart(2, '0') }
        return "${code.take(3)}-${code.drop(3)}"
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
                    verificationCode = json.getString("verificationCode"),
                    onionAddress = if (json.has("onionAddress")) json.getString("onionAddress") else null
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ContactCard
        return userId == other.userId
    }

    override fun hashCode(): Int = userId.hashCode()
}
