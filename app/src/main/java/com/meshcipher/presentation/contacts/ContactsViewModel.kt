package com.meshcipher.presentation.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.repository.ConversationRepository
import com.meshcipher.domain.usecase.GetContactsUseCase
import com.meshcipher.domain.usecase.StartConversationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(
    getContactsUseCase: GetContactsUseCase,
    private val startConversationUseCase: StartConversationUseCase,
    conversationRepository: ConversationRepository
) : ViewModel() {

    val contacts: StateFlow<List<Contact>> = getContactsUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val unreadCounts: StateFlow<Map<String, Int>> = conversationRepository.getUnreadCountsByContact()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    fun startConversation(contactId: String, onConversationReady: (String) -> Unit) {
        viewModelScope.launch {
            val conversationId = startConversationUseCase(contactId)
            onConversationReady(conversationId)
        }
    }
}
