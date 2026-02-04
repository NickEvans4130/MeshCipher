package com.meshcipher.presentation.contacts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.local.database.ConversationDao
import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.model.MessageExpiryMode
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.signal.libsignal.protocol.SignalProtocolAddress
import javax.inject.Inject

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val contactRepository: ContactRepository,
    private val conversationRepository: ConversationRepository,
    private val conversationDao: ConversationDao
) : ViewModel() {

    private val contactId: String = savedStateHandle.get<String>("contactId")
        ?: throw IllegalArgumentException("contactId required")

    val contact: StateFlow<Contact?> = contactRepository.getContactFlow(contactId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _editName = MutableStateFlow("")
    val editName: StateFlow<String> = _editName.asStateFlow()

    private val _editUserId = MutableStateFlow("")
    val editUserId: StateFlow<String> = _editUserId.asStateFlow()

    private val _isEditing = MutableStateFlow(false)
    val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _conversationExpiryMode = MutableStateFlow<String?>(null)
    val conversationExpiryMode: StateFlow<String?> = _conversationExpiryMode.asStateFlow()

    init {
        loadConversationExpiryMode()
        viewModelScope.launch {
            contact.collect { c ->
                if (c != null && !_isEditing.value) {
                    _editName.value = c.displayName
                    _editUserId.value = c.signalProtocolAddress.name
                }
            }
        }
    }

    private fun loadConversationExpiryMode() {
        viewModelScope.launch {
            val conversation = conversationDao.getConversationByContactId(contactId)
            _conversationExpiryMode.value = conversation?.messageExpiryMode
        }
    }

    fun setConversationExpiryMode(mode: MessageExpiryMode?) {
        viewModelScope.launch {
            val conversation = conversationDao.getConversationByContactId(contactId)
            if (conversation != null) {
                conversationDao.setMessageExpiryMode(conversation.id, mode?.name)
                _conversationExpiryMode.value = mode?.name
            }
        }
    }

    fun startEditing() {
        contact.value?.let { c ->
            _editName.value = c.displayName
            _editUserId.value = c.signalProtocolAddress.name
            _isEditing.value = true
        }
    }

    fun cancelEditing() {
        _isEditing.value = false
        contact.value?.let { c ->
            _editName.value = c.displayName
            _editUserId.value = c.signalProtocolAddress.name
        }
    }

    fun updateName(name: String) {
        _editName.value = name
    }

    fun updateUserId(userId: String) {
        _editUserId.value = userId
    }

    fun saveChanges(onSuccess: () -> Unit) {
        val currentContact = contact.value ?: return
        if (_editName.value.isBlank() || _editUserId.value.isBlank()) return

        viewModelScope.launch {
            _isSaving.value = true
            try {
                val updatedContact = currentContact.copy(
                    displayName = _editName.value.trim(),
                    signalProtocolAddress = SignalProtocolAddress(
                        _editUserId.value.trim(),
                        1
                    )
                )
                contactRepository.updateContact(updatedContact)
                _isEditing.value = false
                onSuccess()
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteContact(onDeleted: () -> Unit) {
        val currentContact = contact.value ?: return

        viewModelScope.launch {
            _isDeleting.value = true
            try {
                contactRepository.deleteContact(currentContact)
                onDeleted()
            } finally {
                _isDeleting.value = false
            }
        }
    }

    fun startConversation(onConversationReady: (String) -> Unit) {
        val currentContact = contact.value ?: return

        viewModelScope.launch {
            val conversationId = conversationRepository.createOrGetConversation(currentContact.id)
            onConversationReady(conversationId)
        }
    }
}
