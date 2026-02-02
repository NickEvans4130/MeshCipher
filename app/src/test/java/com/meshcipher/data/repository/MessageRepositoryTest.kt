package com.meshcipher.data.repository

import app.cash.turbine.test
import com.meshcipher.data.local.database.MessageDao
import com.meshcipher.data.local.entity.MessageEntity
import com.meshcipher.domain.model.Message
import com.meshcipher.domain.model.MessageStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MessageRepositoryTest {

    private lateinit var messageDao: MessageDao
    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setup() {
        messageDao = mockk(relaxed = true)
        repository = MessageRepositoryImpl(messageDao)
    }

    @Test
    fun `getMessagesForConversation returns mapped messages`() = runTest {
        // Given
        val conversationId = "conv-1"
        val messageEntities = listOf(
            MessageEntity(
                id = "msg-1",
                conversationId = conversationId,
                senderId = "user-1",
                recipientId = "user-2",
                encryptedContent = "Hello".toByteArray(),
                timestamp = 1000L,
                status = "SENT"
            )
        )

        every { messageDao.getMessagesForConversation(conversationId) } returns flowOf(messageEntities)

        // When
        repository.getMessagesForConversation(conversationId).test {
            val messages = awaitItem()

            // Then
            assertEquals(1, messages.size)
            assertEquals("msg-1", messages[0].id)
            assertEquals("Hello", messages[0].content)
            assertEquals(MessageStatus.SENT, messages[0].status)
            awaitComplete()
        }
    }

    @Test
    fun `insertMessage inserts entity`() = runTest {
        // Given
        val message = Message(
            id = "msg-1",
            conversationId = "conv-1",
            senderId = "user-1",
            recipientId = "user-2",
            content = "Hello",
            timestamp = 1000L,
            status = MessageStatus.PENDING
        )

        // When
        repository.insertMessage(message)

        // Then
        coVerify { messageDao.insertMessage(any()) }
    }
}
