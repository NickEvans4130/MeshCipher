package com.meshcipher.presentation.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.model.Conversation
import com.meshcipher.domain.model.Message
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.repository.ConversationRepository
import com.meshcipher.domain.repository.MessageRepository
import com.meshcipher.domain.usecase.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SendingState {
    object Idle : SendingState()
    object Sending : SendingState()
    object Sent : SendingState()
    data class Error(val message: String) : SendingState()
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val contactRepository: ContactRepository,
    private val sendMessageUseCase: SendMessageUseCase
) : ViewModel() {

    private val conversationId: String = savedStateHandle.get<String>("conversationId")
        ?: throw IllegalArgumentException("conversationId required")

    val messages: StateFlow<List<Message>> = messageRepository
        .getMessagesForConversation(conversationId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val conversation: StateFlow<Conversation?> = conversationRepository
        .getConversationFlow(conversationId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val contact: StateFlow<Contact?> = conversation
        .flatMapLatest { conv ->
            if (conv != null) {
                contactRepository.getContactFlow(conv.contactId)
            } else {
                flowOf(null)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _messageInput = MutableStateFlow("")
    val messageInput = _messageInput.asStateFlow()

    private val _sendingState = MutableStateFlow<SendingState>(SendingState.Idle)
    val sendingState = _sendingState.asStateFlow()

    fun updateMessageInput(text: String) {
        _messageInput.value = text
    }

    fun sendMessage() {
        val content = _messageInput.value.trim()
        if (content.isEmpty()) return

        val contactValue = contact.value ?: return
        val conv = conversation.value ?: return

        _sendingState.value = SendingState.Sending
        _messageInput.value = ""

        viewModelScope.launch {
            val result = sendMessageUseCase(
                conversationId = conversationId,
                contactId = conv.contactId,
                senderId = "me",
                content = content
            )

            if (result.isSuccess) {
                _sendingState.value = SendingState.Sent
                conversationRepository.markConversationAsRead(conversationId)
            } else {
                _sendingState.value = SendingState.Error(
                    result.exceptionOrNull()?.message ?: "Send failed"
                )
            }
        }
    }

    fun clearSendingState() {
        _sendingState.value = SendingState.Idle
    }
}
