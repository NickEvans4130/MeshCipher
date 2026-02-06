package com.meshcipher.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val USER_ID = stringPreferencesKey("user_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val CONNECTION_MODE = stringPreferencesKey("connection_mode")
        val MESH_ENABLED = booleanPreferencesKey("mesh_enabled")
        val MESSAGE_EXPIRY_MODE = stringPreferencesKey("message_expiry_mode")
        val ONION_ADDRESS = stringPreferencesKey("onion_address")
    }

    val userId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.USER_ID]
    }

    val displayName: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.DISPLAY_NAME]
    }

    val onboardingComplete: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_COMPLETE] ?: false
    }

    suspend fun setUserId(userId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.USER_ID] = userId
        }
    }

    suspend fun setDisplayName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DISPLAY_NAME] = name
        }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETE] = complete
        }
    }

    val connectionMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.CONNECTION_MODE] ?: "DIRECT"
    }

    suspend fun setConnectionMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CONNECTION_MODE] = mode
        }
    }

    val meshEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.MESH_ENABLED] ?: false
    }

    suspend fun setMeshEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MESH_ENABLED] = enabled
        }
    }

    val messageExpiryMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.MESSAGE_EXPIRY_MODE] ?: "NEVER"
    }

    suspend fun setMessageExpiryMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MESSAGE_EXPIRY_MODE] = mode
        }
    }

    val onionAddress: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.ONION_ADDRESS]
    }

    suspend fun setOnionAddress(address: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ONION_ADDRESS] = address
        }
    }
}
