package com.meshcipher.data.tor

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores and retrieves configured Tor bridges (GAP-09 / R-11).
 * Bridges are persisted in EncryptedSharedPreferences as a JSON array.
 */
@Singleton
class TorBridgeRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "tor_bridge_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _bridges = MutableStateFlow<List<TorBridge>>(emptyList())
    val bridges: StateFlow<List<TorBridge>> = _bridges.asStateFlow()

    init {
        _bridges.value = loadFromPrefs()
    }

    fun getBridges(): List<TorBridge> = _bridges.value

    fun addBridge(bridge: TorBridge) {
        val updated = _bridges.value + bridge
        _bridges.value = updated
        saveToPrefs(updated)
        Timber.d("Bridge added: %s", bridge.address)
    }

    fun removeBridge(bridge: TorBridge) {
        val updated = _bridges.value.filter { it != bridge }
        _bridges.value = updated
        saveToPrefs(updated)
        Timber.d("Bridge removed: %s", bridge.address)
    }

    fun clearBridges() {
        _bridges.value = emptyList()
        prefs.edit().remove(KEY_BRIDGES).apply()
    }

    private fun saveToPrefs(bridges: List<TorBridge>) {
        prefs.edit().putString(KEY_BRIDGES, gson.toJson(bridges)).apply()
    }

    private fun loadFromPrefs(): List<TorBridge> {
        val json = prefs.getString(KEY_BRIDGES, null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<TorBridge>>() {}.type)
        } catch (e: Exception) {
            Timber.w(e, "Failed to load bridges from prefs")
            emptyList()
        }
    }

    companion object {
        private const val KEY_BRIDGES = "configured_bridges"
    }
}
