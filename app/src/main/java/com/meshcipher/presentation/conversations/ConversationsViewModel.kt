package com.meshcipher.presentation.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.domain.model.ConnectionMode
import com.meshcipher.domain.model.Conversation
import com.meshcipher.domain.usecase.GetConversationsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    getConversationsUseCase: GetConversationsUseCase,
    appPreferences: AppPreferences
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
}
