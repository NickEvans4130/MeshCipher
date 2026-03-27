package com.meshcipher.domain.model

// MD-01: Privacy profile tiers. Controls which metadata-protection mitigations are active.
// STANDARD  — no additional mitigations beyond Sprint 1-3 controls.
// HIGH_PRIVACY — activates MD-02 (message padding), MD-03 (BLE interval randomisation),
//               MD-04 (layered routing header encryption).
// MAXIMUM   — reserved for future mitigations (Sphinx packets, cover traffic). Behaves
//             identically to HIGH_PRIVACY until those mitigations are implemented.
enum class PrivacyProfile {
    STANDARD,
    HIGH_PRIVACY,
    MAXIMUM;

    // Returns true if metadata-protection mitigations (MD-02, MD-03, MD-04) should activate.
    fun isEnhanced(): Boolean = this == HIGH_PRIVACY || this == MAXIMUM
}
