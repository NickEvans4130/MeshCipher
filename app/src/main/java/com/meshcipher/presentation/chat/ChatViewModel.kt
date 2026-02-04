package com.meshcipher.presentation.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.media.MediaManager
import com.meshcipher.data.media.VoiceNoteRecorder
import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.model.Conversation
import com.meshcipher.domain.model.MediaMetadata
import com.meshcipher.domain.model.MediaType
import com.meshcipher.domain.model.Message
import com.meshcipher.domain.model.MessageStatus
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.repository.ConversationRepository
import com.meshcipher.domain.repository.MessageRepository
import com.meshcipher.domain.usecase.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
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
    private val sendMessageUseCase: SendMessageUseCase,
    private val mediaManager: MediaManager,
    private val voiceNoteRecorder: VoiceNoteRecorder
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

    @OptIn(ExperimentalCoroutinesApi::class)
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

    val uploadProgress: StateFlow<Float> = mediaManager.uploadProgress
    val downloadProgress: StateFlow<Float> = mediaManager.downloadProgress

    val isRecording: StateFlow<Boolean> = voiceNoteRecorder.isRecording
    val recordingDuration: StateFlow<Long> = voiceNoteRecorder.durationMs

    private val _showMediaPicker = MutableStateFlow(false)
    val showMediaPicker = _showMediaPicker.asStateFlow()

    init {
        viewModelScope.launch {
            conversationRepository.markConversationAsRead(conversationId)
        }
    }

    fun markAsRead() {
        viewModelScope.launch {
            conversationRepository.markConversationAsRead(conversationId)
        }
    }

    fun updateMessageInput(text: String) {
        _messageInput.value = text
    }

    fun sendMessage() {
        val content = _messageInput.value.trim()
        if (content.isEmpty()) return

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

    fun toggleMediaPicker() {
        _showMediaPicker.value = !_showMediaPicker.value
    }

    fun dismissMediaPicker() {
        _showMediaPicker.value = false
    }

    fun sendPhoto(uri: Uri) {
        _showMediaPicker.value = false
        _sendingState.value = SendingState.Sending

        viewModelScope.launch {
            val result = mediaManager.sendPhoto(uri)
            handleMediaResult(result, MediaType.PHOTO)
        }
    }

    fun sendVideo(uri: Uri) {
        _showMediaPicker.value = false
        _sendingState.value = SendingState.Sending

        viewModelScope.launch {
            val result = mediaManager.sendVideo(uri)
            handleMediaResult(result, MediaType.VIDEO)
        }
    }

    fun startVoiceRecording() {
        voiceNoteRecorder.startRecording()
    }

    fun stopVoiceRecording() {
        val result = voiceNoteRecorder.stopRecording()
        if (result.isSuccess) {
            val recording = result.getOrThrow()
            _sendingState.value = SendingState.Sending

            viewModelScope.launch {
                val mediaResult = mediaManager.sendVoiceNote(recording.file, recording.durationMs)
                handleMediaResult(mediaResult, MediaType.AUDIO)
            }
        }
    }

    fun cancelVoiceRecording() {
        voiceNoteRecorder.cancelRecording()
    }

    private suspend fun handleMediaResult(result: Result<MediaMetadata>, mediaType: MediaType) {
        if (result.isSuccess) {
            val metadata = result.getOrThrow()
            val conv = conversation.value ?: return

            val caption = when (mediaType) {
                MediaType.PHOTO -> "[Photo]"
                MediaType.VIDEO -> "[Video]"
                MediaType.AUDIO -> "[Voice Note]"
                MediaType.FILE -> "[File]"
            }

            val message = Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderId = "me",
                recipientId = conv.contactId,
                content = caption,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENT,
                isOwnMessage = true,
                mediaId = metadata.id,
                mediaType = mediaType,
                mediaMetadataJson = metadata.toJson()
            )

            messageRepository.insertMessage(message)
            conversationRepository.markConversationAsRead(conversationId)
            _sendingState.value = SendingState.Sent
            Timber.d("Media message saved: %s (%s)", metadata.id, mediaType)
        } else {
            _sendingState.value = SendingState.Error(
                result.exceptionOrNull()?.message ?: "Media send failed"
            )
            Timber.e(result.exceptionOrNull(), "Media send failed")
        }
    }

    fun clearSendingState() {
        _sendingState.value = SendingState.Idle
    }
}
