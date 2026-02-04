package com.meshcipher.domain.usecase

import com.meshcipher.domain.repository.ConversationRepository
import javax.inject.Inject

class StartConversationUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository
) {
    suspend operator fun invoke(contactId: String): String {
        return conversationRepository.createOrGetConversation(contactId)
    }
}
