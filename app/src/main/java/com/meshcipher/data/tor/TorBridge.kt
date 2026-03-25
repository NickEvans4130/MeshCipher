package com.meshcipher.data.tor

/**
 * Represents a single Tor bridge entry (GAP-09 / R-11).
 *
 * @param type Transport type, e.g. "obfs4"
 * @param address IP:port of the bridge
 * @param fingerprint Optional relay fingerprint
 * @param cert Optional obfs4 certificate parameter
 */
data class TorBridge(
    val type: String,
    val address: String,
    val fingerprint: String? = null,
    val cert: String? = null
) {
    /**
     * Serialises to the torrc Bridge line format:
     *   obfs4 <address> [fingerprint] [cert=<cert> iat-mode=0]
     */
    fun toBridgeLine(): String = buildString {
        append(type)
        append(" ")
        append(address)
        if (!fingerprint.isNullOrBlank()) {
            append(" ")
            append(fingerprint)
        }
        if (!cert.isNullOrBlank()) {
            append(" cert=")
            append(cert)
            append(" iat-mode=0")
        }
    }

    companion object {
        /**
         * Parses a bridge line of the form:
         *   obfs4 <ip:port> [fingerprint] [cert=<cert>] [iat-mode=<n>]
         * Returns null if the format is invalid.
         */
        fun parse(line: String): TorBridge? {
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size < 2) return null
            val type = parts[0]
            val address = parts[1]
            if (!address.matches(Regex(".+:\\d{1,5}"))) return null
            var fingerprint: String? = null
            var cert: String? = null
            for (i in 2 until parts.size) {
                when {
                    parts[i].startsWith("cert=") -> cert = parts[i].removePrefix("cert=")
                    parts[i].startsWith("iat-mode=") -> { /* handled above */ }
                    !parts[i].contains("=") -> if (fingerprint == null) fingerprint = parts[i]
                }
            }
            return TorBridge(type = type, address = address, fingerprint = fingerprint, cert = cert)
        }

        /** Validation: returns an error message or null if valid. */
        fun validate(line: String): String? {
            val bridge = parse(line) ?: return "Invalid bridge format. Expected: <type> <ip:port> [fingerprint] [cert=<cert>]"
            if (bridge.address.split(":").lastOrNull()?.toIntOrNull()?.let { it !in 1..65535 } != false) {
                return "Invalid port number in bridge address"
            }
            return null
        }
    }
}
