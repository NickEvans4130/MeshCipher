package com.meshcipher.presentation.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.data.relay.WebSocketManager
import com.meshcipher.data.relay.WebSocketState
import com.meshcipher.domain.model.ConnectionMode
import com.meshcipher.domain.model.Conversation
import com.meshcipher.domain.usecase.GetConversationsUseCase
import com.meshcipher.domain.usecase.ReceiveMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    getConversationsUseCase: GetConversationsUseCase,
    private val receiveMessageUseCase: ReceiveMessageUseCase,
    private val appPreferences: AppPreferences,
    private val webSocketManager: WebSocketManager
) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> = getConversationsUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val connectionMode: StateFlow<ConnectionMode> = appPreferences.connectionMode
        .map { name ->
            try {
                ConnectionMode.valueOf(name)
            } catch (e: IllegalArgumentException) {
                ConnectionMode.DIRECT
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ConnectionMode.DIRECT
        )

    val wsState: StateFlow<WebSocketState> = webSocketManager.connectionState

    init {
        // Fallback polling: only polls when WebSocket is disconnected
        viewModelScope.launch {
            while (true) {
                if (webSocketManager.connectionState.value != WebSocketState.CONNECTED) {
                    pollMessages()
                }
                delay(15_000)
            }
        }
    }

    private suspend fun pollMessages() {
        val userId = appPreferences.userId.firstOrNull()
        if (userId.isNullOrBlank()) return
        try {
            receiveMessageUseCase(userId)
        } catch (e: Exception) {
            Timber.w(e, "Message poll failed")
        }
    }
}
