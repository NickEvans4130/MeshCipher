package com.meshcipher.presentation.contacts

import android.util.Base64
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.repository.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AddContactViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val contactRepository: ContactRepository
) : ViewModel() {

    private val _name = MutableStateFlow("")
    val name = _name.asStateFlow()

    private val _identifier = MutableStateFlow(savedStateHandle.get<String>("userId") ?: "")
    val identifier = _identifier.asStateFlow()

    val isFromQRScan: Boolean = savedStateHandle.get<String>("userId") != null

    val scannedOnionAddress: String? = savedStateHandle.get<String>("onionAddress")
    private val scannedPublicKey: String? = savedStateHandle.get<String>("publicKey")
    private val scannedDeviceId: String? = savedStateHandle.get<String>("deviceId")

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _contactAdded = MutableStateFlow(false)
    val contactAdded = _contactAdded.asStateFlow()

    fun updateName(value: String) {
        _name.value = value
    }

    fun updateIdentifier(value: String) {
        _identifier.value = value
    }

    fun addContact() {
        if (_name.value.isBlank() || _identifier.value.isBlank()) return

        viewModelScope.launch {
            _isLoading.value = true

            try {
                val publicKey = if (scannedPublicKey != null) {
                    Base64.decode(scannedPublicKey, Base64.NO_WRAP or Base64.URL_SAFE)
                } else {
                    ByteArray(32) { 0 }
                }

                val contact = Contact(
                    id = _identifier.value,
                    displayName = _name.value,
                    publicKey = publicKey,
                    identityKey = publicKey,
                    lastSeen = System.currentTimeMillis(),
                    onionAddress = scannedOnionAddress
                )

                contactRepository.insertContact(contact)
                _contactAdded.value = true
            } finally {
                _isLoading.value = false
            }
        }
    }
}
