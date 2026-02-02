package com.meshcipher.domain.usecase

import com.meshcipher.domain.model.Conversation
import com.meshcipher.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetConversationsUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository
) {
    operator fun invoke(): Flow<List<Conversation>> {
        return conversationRepository.getAllConversations()
    }
}
