package com.meshcipher.data.auth

import com.meshcipher.BuildConfig

/**
 * SPKI pin hashes for the relay server TLS leaf certificate (GAP-03 / R-04).
 *
 * Values are injected at build time from local.properties (gitignored). Override the
 * following keys before building a production release:
 *
 *   relay.host=relay.meshcipher.net
 *   relay.cert.pin.primary=sha256/<base64-SHA256-of-primary-SPKI>
 *   relay.cert.pin.backup=sha256/<base64-SHA256-of-backup-SPKI>
 *
 * To derive the SPKI hash for a given certificate:
 *   openssl x509 -in cert.pem -pubkey -noout \
 *     | openssl pkey -pubin -outform der \
 *     | openssl dgst -sha256 -binary | base64
 *
 * A startup check in [validate] throws [IllegalStateException] if placeholder values are
 * still present in a non-debug build, ensuring pinned requests cannot proceed with dummy values.
 */
object CertificatePins {

    val RELAY_HOST: String = BuildConfig.RELAY_HOST
    val RELAY_CERT_PIN_PRIMARY: String = BuildConfig.RELAY_CERT_PIN_PRIMARY
    val RELAY_CERT_PIN_BACKUP: String = BuildConfig.RELAY_CERT_PIN_BACKUP

    private val PLACEHOLDER_PATTERNS = listOf(
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
    )

    /**
     * Called at app startup. Throws [IllegalStateException] in release builds if any pin is
     * still the placeholder value, preventing requests from proceeding with dummy pins.
     */
    fun validate() {
        if (BuildConfig.DEBUG) return // Allow placeholder values in debug/test builds
        val invalid = listOf(RELAY_CERT_PIN_PRIMARY, RELAY_CERT_PIN_BACKUP)
            .any { pin -> PLACEHOLDER_PATTERNS.any { placeholder -> pin.contains(placeholder) } }
        check(!invalid) {
            "CertificatePins contains placeholder values. " +
            "Set relay.cert.pin.primary and relay.cert.pin.backup in local.properties " +
            "before building a release."
        }
    }
}
