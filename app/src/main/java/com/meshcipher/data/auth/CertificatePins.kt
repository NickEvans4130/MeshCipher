package com.meshcipher.data.auth

/**
 * SPKI pin hashes for the relay server TLS leaf certificate (GAP-03 / R-04).
 *
 * Both pins MUST be updated whenever the relay TLS certificate is rotated,
 * before shipping the new build. Pin to the leaf certificate SPKI hash only —
 * never to a CA or intermediate certificate.
 *
 * To derive the SPKI hash for a given certificate:
 *   openssl x509 -in cert.pem -pubkey -noout \
 *     | openssl pkey -pubin -outform der \
 *     | openssl dgst -sha256 -binary | base64
 *
 * TODO: Replace all placeholder values with the real relay SPKI hashes and hostname
 *       before the first production deployment.
 */
object CertificatePins {

    /**
     * Relay server hostname. Must match the URL the user configures in Settings.
     * Certificate pinning is applied only to requests destined for this host.
     * TODO: Update to the actual relay hostname before production deployment.
     */
    const val RELAY_HOST = "relay.meshcipher.net"

    /**
     * Primary SPKI SHA-256 pin for the relay leaf TLS certificate.
     * Format: "sha256/<base64-encoded-SHA256-of-SPKI-DER>"
     * TODO: Replace with the real pin derived from the production relay certificate.
     */
    const val RELAY_CERT_PIN_PRIMARY = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    /**
     * Backup SPKI pin for certificate rotation events.
     * Set this to the next certificate's pin before rotating, then swap PRIMARY and BACKUP.
     * TODO: Replace with the real backup pin before production deployment.
     */
    const val RELAY_CERT_PIN_BACKUP = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
}
