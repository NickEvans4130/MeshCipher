package com.meshcipher.presentation.contacts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.repository.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.signal.libsignal.protocol.SignalProtocolAddress
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

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    fun updateName(value: String) {
        _name.value = value
    }

    fun updateIdentifier(value: String) {
        _identifier.value = value
    }

    fun addContact(onSuccess: () -> Unit) {
        if (_name.value.isBlank() || _identifier.value.isBlank()) return

        viewModelScope.launch {
            _isLoading.value = true

            try {
                // TODO: Exchange keys properly
                val contact = Contact(
                    id = UUID.randomUUID().toString(),
                    displayName = _name.value,
                    publicKey = ByteArray(32) { 0 },
                    identityKey = ByteArray(32) { 0 },
                    signalProtocolAddress = SignalProtocolAddress(
                        _identifier.value,
                        1
                    ),
                    lastSeen = System.currentTimeMillis(),
                    onionAddress = scannedOnionAddress
                )

                contactRepository.insertContact(contact)
                onSuccess()
            } finally {
                _isLoading.value = false
            }
        }
    }
}
