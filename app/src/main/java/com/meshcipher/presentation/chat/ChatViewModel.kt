package com.meshcipher.presentation.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.media.MediaEncryptor
import com.meshcipher.data.media.MediaFileManager
import com.meshcipher.data.media.MediaProcessor
import com.meshcipher.data.media.VoicePlayer
import com.meshcipher.data.media.VoiceRecorder
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.data.relay.WebSocketManager
import com.meshcipher.data.relay.WebSocketState
import com.meshcipher.domain.model.Contact
import com.meshcipher.domain.model.Conversation
import com.meshcipher.domain.model.MediaAttachment
import com.meshcipher.domain.model.MediaType
import com.meshcipher.domain.model.Message
import com.meshcipher.domain.repository.ContactRepository
import com.meshcipher.domain.repository.ConversationRepository
import com.meshcipher.domain.repository.MessageRepository
import com.meshcipher.domain.usecase.ReceiveMessageUseCase
import com.meshcipher.domain.usecase.SendMediaMessageUseCase
import com.meshcipher.domain.usecase.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
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
    private val sendMediaMessageUseCase: SendMediaMessageUseCase,
    private val receiveMessageUseCase: ReceiveMessageUseCase,
    private val identityManager: IdentityManager,
    private val appPreferences: AppPreferences,
    private val webSocketManager: WebSocketManager,
    private val mediaProcessor: MediaProcessor,
    private val mediaEncryptor: MediaEncryptor,
    private val mediaFileManager: MediaFileManager,
    val voiceRecorder: VoiceRecorder,
    val voicePlayer: VoicePlayer
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

    private val _mediaPickerVisible = MutableStateFlow(false)
    val mediaPickerVisible = _mediaPickerVisible.asStateFlow()

    private val _mediaSendingProgress = MutableStateFlow<Float?>(null)
    val mediaSendingProgress = _mediaSendingProgress.asStateFlow()

    init {
        // Mark conversation as read when chat is opened
        viewModelScope.launch {
            conversationRepository.markConversationAsRead(conversationId)
        }
        // Auto-mark as read when new messages arrive via WebSocket
        viewModelScope.launch {
            messages.collect {
                conversationRepository.markConversationAsRead(conversationId)
            }
        }
        // Fallback polling when WebSocket is disconnected
        viewModelScope.launch {
            val userId = appPreferences.userId.firstOrNull()
            if (!userId.isNullOrBlank()) {
                // Immediate poll on chat open for instant first message
                try {
                    receiveMessageUseCase(userId)
                    conversationRepository.markConversationAsRead(conversationId)
                } catch (_: Exception) {}
                // Then poll every 3s only when WS is down
                while (true) {
                    delay(3_000)
                    if (webSocketManager.connectionState.value != WebSocketState.CONNECTED) {
                        try {
                            receiveMessageUseCase(userId)
                            conversationRepository.markConversationAsRead(conversationId)
                        } catch (e: Exception) {
                            Timber.w(e, "Chat poll failed")
                        }
                    }
                }
            }
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

        val contactValue = contact.value ?: return
        val conv = conversation.value ?: return

        _sendingState.value = SendingState.Sending
        _messageInput.value = ""

        viewModelScope.launch {
            val identity = identityManager.getIdentity()
            if (identity == null) {
                _sendingState.value = SendingState.Error("No identity available")
                return@launch
            }

            val result = sendMessageUseCase(
                conversationId = conversationId,
                contactId = conv.contactId,
                senderId = identity.userId,
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
        _mediaPickerVisible.value = !_mediaPickerVisible.value
    }

    fun dismissMediaPicker() {
        _mediaPickerVisible.value = false
    }

    fun sendMediaMessage(context: Context, uri: Uri, mediaType: MediaType) {
        _mediaPickerVisible.value = false
        val conv = conversation.value ?: return

        _sendingState.value = SendingState.Sending
        _mediaSendingProgress.value = 0f

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    processAndSendMedia(context, uri, mediaType, conv.contactId)
                }

                if (result.isSuccess) {
                    _sendingState.value = SendingState.Sent
                    conversationRepository.markConversationAsRead(conversationId)
                } else {
                    _sendingState.value = SendingState.Error(
                        result.exceptionOrNull()?.message ?: "Media send failed"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to send media")
                _sendingState.value = SendingState.Error(e.message ?: "Media send failed")
            } finally {
                _mediaSendingProgress.value = null
            }
        }
    }

    private suspend fun processAndSendMedia(
        context: Context,
        uri: Uri,
        mediaType: MediaType,
        contactId: String
    ): Result<Message> {
        _mediaSendingProgress.value = 0.1f

        val processedFile = when (mediaType) {
            MediaType.IMAGE -> mediaProcessor.processImage(context, uri)
            MediaType.VIDEO -> mediaProcessor.processVideo(context, uri)
            MediaType.VOICE -> {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Cannot open URI")
                val tempFile = File(context.cacheDir, "voice_send_${System.currentTimeMillis()}.aac")
                inputStream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                tempFile.deleteOnExit()
                tempFile
            }
        }

        _mediaSendingProgress.value = 0.3f

        val fileBytes = processedFile.readBytes()
        val encrypted = mediaEncryptor.encrypt(fileBytes)

        _mediaSendingProgress.value = 0.5f

        val mediaId = UUID.randomUUID().toString()
        val localPath = mediaFileManager.saveMedia(context, mediaId, fileBytes, mediaType)

        val fileName = getFileName(context, uri) ?: processedFile.name
        val mimeType = getMimeType(context, uri, mediaType)

        var width: Int? = null
        var height: Int? = null
        var durationMs: Long? = null

        when (mediaType) {
            MediaType.IMAGE -> {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(processedFile.absolutePath, options)
                width = options.outWidth
                height = options.outHeight
            }
            MediaType.VIDEO, MediaType.VOICE -> {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(processedFile.absolutePath)
                    durationMs = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull()
                    if (mediaType == MediaType.VIDEO) {
                        width = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                        )?.toIntOrNull()
                        height = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                        )?.toIntOrNull()
                    }
                    retriever.release()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to extract media metadata")
                }
            }
        }

        _mediaSendingProgress.value = 0.6f

        val attachment = MediaAttachment(
            mediaId = mediaId,
            mediaType = mediaType,
            fileName = fileName,
            fileSize = fileBytes.size.toLong(),
            mimeType = mimeType,
            localPath = localPath,
            durationMs = durationMs,
            width = width,
            height = height,
            encryptionKey = encrypted.keyBase64,
            encryptionIv = encrypted.ivBase64
        )

        _mediaSendingProgress.value = 0.7f

        val identity = identityManager.getIdentity()
            ?: return Result.failure(Exception("No identity available"))

        val result = sendMediaMessageUseCase(
            conversationId = conversationId,
            contactId = contactId,
            senderId = identity.userId,
            mediaAttachment = attachment,
            encryptedFileBytes = encrypted.encryptedBytes
        )

        // Cleanup temp file
        if (processedFile.absolutePath != localPath) {
            processedFile.delete()
        }

        _mediaSendingProgress.value = 1.0f
        return result
    }

    fun sendVoiceMessage(context: Context) {
        val file = voiceRecorder.stop() ?: return
        val conv = conversation.value ?: return

        _sendingState.value = SendingState.Sending
        _mediaSendingProgress.value = 0f

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val fileBytes = file.readBytes()
                    val encrypted = mediaEncryptor.encrypt(fileBytes)

                    val mediaId = UUID.randomUUID().toString()
                    val localPath = mediaFileManager.saveMedia(context, mediaId, fileBytes, MediaType.VOICE)

                    var durationMs: Long? = null
                    try {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(file.absolutePath)
                        durationMs = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLongOrNull()
                        retriever.release()
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to get voice duration")
                    }

                    val attachment = MediaAttachment(
                        mediaId = mediaId,
                        mediaType = MediaType.VOICE,
                        fileName = file.name,
                        fileSize = fileBytes.size.toLong(),
                        mimeType = "audio/aac",
                        localPath = localPath,
                        durationMs = durationMs,
                        encryptionKey = encrypted.keyBase64,
                        encryptionIv = encrypted.ivBase64
                    )

                    val identity = identityManager.getIdentity()
                        ?: return@withContext Result.failure(Exception("No identity available"))

                    sendMediaMessageUseCase(
                        conversationId = conversationId,
                        contactId = conv.contactId,
                        senderId = identity.userId,
                        mediaAttachment = attachment,
                        encryptedFileBytes = encrypted.encryptedBytes
                    )
                }

                if (result.isSuccess) {
                    _sendingState.value = SendingState.Sent
                } else {
                    _sendingState.value = SendingState.Error(
                        result.exceptionOrNull()?.message ?: "Voice send failed"
                    )
                }
            } catch (e: Exception) {
                _sendingState.value = SendingState.Error(e.message ?: "Voice send failed")
            } finally {
                _mediaSendingProgress.value = null
                file.delete()
            }
        }
    }

    fun startVoiceRecording(context: Context) {
        voiceRecorder.start(context)
    }

    fun cancelVoiceRecording() {
        voiceRecorder.cancel()
    }

    fun clearSendingState() {
        _sendingState.value = SendingState.Idle
    }

    /**
     * Decrypts media to an in-memory byte array (for images).
     */
    fun decryptMediaBytes(mediaId: String, mediaType: MediaType): ByteArray? {
        return mediaFileManager.decryptMedia(mediaId, mediaType)
    }

    /**
     * Decrypts media to a temporary file (for video/voice playback).
     * Caller should delete the file when done.
     */
    fun decryptMediaToTempFile(mediaId: String, mediaType: MediaType): File? {
        return mediaFileManager.decryptMediaToTempFile(mediaId, mediaType)
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getMimeType(context: Context, uri: Uri, mediaType: MediaType): String {
        return context.contentResolver.getType(uri) ?: when (mediaType) {
            MediaType.IMAGE -> "image/jpeg"
            MediaType.VIDEO -> "video/mp4"
            MediaType.VOICE -> "audio/aac"
        }
    }
}
