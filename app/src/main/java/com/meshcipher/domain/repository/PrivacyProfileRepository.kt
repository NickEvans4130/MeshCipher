package com.meshcipher.domain.repository

import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.domain.model.PrivacyProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// MD-01: Repository for the persisted PrivacyProfile setting.
@Singleton
class PrivacyProfileRepository @Inject constructor(
    private val appPreferences: AppPreferences
) {
    val privacyProfile: Flow<PrivacyProfile> = appPreferences.privacyProfile.map { name ->
        name.toPrivacyProfile()
    }

    suspend fun getPrivacyProfile(): PrivacyProfile =
        appPreferences.privacyProfile.first().toPrivacyProfile()

    suspend fun setPrivacyProfile(profile: PrivacyProfile) {
        appPreferences.setPrivacyProfile(profile.name)
    }

    private fun String.toPrivacyProfile(): PrivacyProfile = try {
        PrivacyProfile.valueOf(this)
    } catch (e: IllegalArgumentException) {
        PrivacyProfile.STANDARD
    }
}
