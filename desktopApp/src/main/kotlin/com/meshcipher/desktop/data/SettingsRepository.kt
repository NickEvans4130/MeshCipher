package com.meshcipher.desktop.data

import com.meshcipher.desktop.platform.DesktopPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Properties

/**
 * Persists user preferences to ~/.config/meshcipher/settings.properties.
 * Backed by a StateFlow so UI can observe changes reactively.
 */
object SettingsRepository {

    private val propsFile = DesktopPlatform.configDir.resolve("settings.properties")

    private val _torEnabled = MutableStateFlow(load())
    val torEnabled: StateFlow<Boolean> = _torEnabled.asStateFlow()

    fun setTorEnabled(enabled: Boolean) {
        _torEnabled.value = enabled
        save()
    }

    private fun load(): Boolean = try {
        if (!propsFile.exists()) false
        else Properties()
            .apply { propsFile.inputStream().use { load(it) } }
            .getProperty("tor_enabled", "false")
            .toBoolean()
    } catch (_: Exception) { false }

    private fun save() {
        try {
            Properties().also {
                it.setProperty("tor_enabled", _torEnabled.value.toString())
                propsFile.outputStream().use { stream ->
                    it.store(stream, "MeshCipher settings")
                }
            }
        } catch (_: Exception) {}
    }
}
