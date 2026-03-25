package com.meshcipher.data.auth

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies the ES256 signature of a JWT token against the relay server's EC public key.
 *
 * GAP-04 / R-05: Prevents Android from trusting tokens whose signature cannot be verified
 * with the known relay public key. A JWT with a tampered payload or forged with a different
 * key will fail verification here before any claims are trusted.
 *
 * JWT ES256 signatures are encoded as the raw R||S byte concatenation (64 bytes for P-256),
 * not DER. Java's SHA256withECDSA Signature expects DER, so we convert before verifying.
 */
object JwtSignatureVerifier {

    /**
     * Returns true if the JWT's ES256 signature is valid for [publicKeyPem].
     *
     * @param token     The raw JWT string (header.payload.signature)
     * @param publicKeyPem  PEM-encoded EC public key (SubjectPublicKeyInfo / PKCS#8 format)
     * @throws SecurityException if the format is invalid or the key cannot be parsed
     */
    fun verify(token: String, publicKeyPem: String): Boolean {
        val parts = token.split(".")
        if (parts.size != 3) throw SecurityException("Invalid JWT format: expected 3 parts")

        val headerPayload = "${parts[0]}.${parts[1]}"
        val signatureB64Url = parts[2]

        // JWT ES256 uses raw R||S (64 bytes); convert to DER for Java Signature
        val rawSignature = try {
            Base64.getUrlDecoder().decode(signatureB64Url)
        } catch (e: IllegalArgumentException) {
            throw SecurityException("JWT signature is not valid base64url", e)
        }
        val derSignature = rawEcdsaToDer(rawSignature)

        val publicKeyBytes = try {
            Base64.getDecoder().decode(
                publicKeyPem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace("\n", "")
                    .trim()
            )
        } catch (e: IllegalArgumentException) {
            throw SecurityException("Relay public key PEM is not valid base64", e)
        }

        val keySpec = X509EncodedKeySpec(publicKeyBytes)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(keySpec)

        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(publicKey)
        sig.update(headerPayload.toByteArray(Charsets.US_ASCII))
        return sig.verify(derSignature)
    }

    /**
     * Converts a raw ECDSA signature (R||S, 64 bytes for P-256) to DER-encoded format.
     * Per RFC 7518 §3.4, JWT ES256 signatures use the fixed-length R||S encoding.
     */
    private fun rawEcdsaToDer(raw: ByteArray): ByteArray {
        if (raw.size != 64) throw SecurityException("ES256 signature must be exactly 64 bytes, got ${raw.size}")
        val r = raw.copyOfRange(0, 32)
        val s = raw.copyOfRange(32, 64)
        val rDer = encodeDerInteger(r)
        val sDer = encodeDerInteger(s)
        val body = rDer + sDer
        return byteArrayOf(0x30.toByte()) + derLength(body.size) + body
    }

    private fun encodeDerInteger(bytes: ByteArray): ByteArray {
        // Strip leading zeros (keep at least 1 byte); prepend 0x00 if high bit is set
        var start = 0
        while (start < bytes.size - 1 && bytes[start] == 0.toByte()) start++
        val trimmed = bytes.copyOfRange(start, bytes.size)
        val value = if (trimmed[0].toInt() and 0x80 != 0) byteArrayOf(0) + trimmed else trimmed
        return byteArrayOf(0x02.toByte()) + derLength(value.size) + value
    }

    private fun derLength(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
    }
}
