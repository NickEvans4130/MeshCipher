package com.meshcipher.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.local.preferences.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class OnboardingUiState(
    val deviceName: String = "",
    val isCreating: Boolean = false,
    val identityCreated: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState = _uiState.asStateFlow()

    fun updateDeviceName(name: String) {
        _uiState.value = _uiState.value.copy(deviceName = name, error = null)
    }

    fun createIdentity() {
        val deviceName = _uiState.value.deviceName.trim()
        if (deviceName.isBlank()) return

        _uiState.value = _uiState.value.copy(isCreating = true, error = null)

        viewModelScope.launch {
            try {
                val identity = identityManager.createIdentity(deviceName)
                appPreferences.setUserId(identity.userId)
                appPreferences.setDisplayName(deviceName)
                appPreferences.setOnboardingComplete(true)

                Timber.d("Identity created: %s", identity.userId)
                _uiState.value = _uiState.value.copy(
                    isCreating = false,
                    identityCreated = true
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to create identity")
                _uiState.value = _uiState.value.copy(
                    isCreating = false,
                    error = e.message ?: "Failed to create identity"
                )
            }
        }
    }
}
