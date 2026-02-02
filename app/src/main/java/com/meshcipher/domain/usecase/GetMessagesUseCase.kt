package com.meshcipher.domain.usecase

import com.meshcipher.domain.model.Message
import com.meshcipher.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetMessagesUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    operator fun invoke(conversationId: String): Flow<List<Message>> {
        return messageRepository.getMessagesForConversation(conversationId)
    }
}
