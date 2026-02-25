package com.meshcipher.presentation.linking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.repository.LinkedDevicesRepository
import com.meshcipher.shared.domain.model.LinkedDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LinkedDevicesViewModel @Inject constructor(
    private val repository: LinkedDevicesRepository
) : ViewModel() {

    val devices: StateFlow<List<LinkedDevice>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun revoke(deviceId: String) {
        viewModelScope.launch { repository.revoke(deviceId) }
    }
}
