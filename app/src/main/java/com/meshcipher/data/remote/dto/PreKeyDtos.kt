package com.meshcipher.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * RM-10 / GAP-08: DTOs for pre-key bundle upload and retrieval.
 * Kyber fields are optional to support backwards compatibility with
 * clients that do not yet implement PQXDH.
 */
data class UploadPreKeyBundleRequest(
    @SerializedName("registration_id") val registrationId: Int,
    @SerializedName("pre_key_id") val preKeyId: Int,
    @SerializedName("pre_key") val preKey: String,          // base64 EC public key
    @SerializedName("signed_pre_key_id") val signedPreKeyId: Int,
    @SerializedName("signed_pre_key") val signedPreKey: String,    // base64 EC public key
    @SerializedName("signed_pre_key_signature") val signedPreKeySignature: String, // base64
    @SerializedName("identity_key") val identityKey: String,       // base64
    // RM-10: optional PQXDH fields
    @SerializedName("kyber_pre_key_id") val kyberPreKeyId: Int? = null,
    @SerializedName("kyber_pre_key") val kyberPreKey: String? = null,          // base64
    @SerializedName("kyber_pre_key_signature") val kyberPreKeySignature: String? = null  // base64
)

data class UploadPreKeyBundleResponse(
    @SerializedName("status") val status: String
)

data class PreKeyBundleResponse(
    @SerializedName("registration_id") val registrationId: Int,
    @SerializedName("pre_key_id") val preKeyId: Int,
    @SerializedName("pre_key") val preKey: String,
    @SerializedName("signed_pre_key_id") val signedPreKeyId: Int,
    @SerializedName("signed_pre_key") val signedPreKey: String,
    @SerializedName("signed_pre_key_signature") val signedPreKeySignature: String,
    @SerializedName("identity_key") val identityKey: String,
    @SerializedName("pqxdh_supported") val pqxdhSupported: Boolean = false,
    @SerializedName("kyber_pre_key_id") val kyberPreKeyId: Int? = null,
    @SerializedName("kyber_pre_key") val kyberPreKey: String? = null,
    @SerializedName("kyber_pre_key_signature") val kyberPreKeySignature: String? = null
)
