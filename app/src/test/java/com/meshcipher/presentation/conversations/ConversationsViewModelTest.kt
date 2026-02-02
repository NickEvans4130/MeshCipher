package com.meshcipher.presentation.conversations

import app.cash.turbine.test
import com.meshcipher.domain.model.Conversation
import com.meshcipher.domain.usecase.GetConversationsUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModelTest {

    private lateinit var getConversationsUseCase: GetConversationsUseCase
    private lateinit var viewModel: ConversationsViewModel

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        getConversationsUseCase = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `conversations flow emits data from use case`() = runTest {
        val conversations = listOf(
            Conversation(
                id = "conv-1",
                contactId = "contact-1",
                contactName = "Alice",
                lastMessage = "Hello",
                lastMessageTime = 1000L,
                unreadCount = 1,
                isPinned = false
            )
        )

        every { getConversationsUseCase() } returns flowOf(conversations)

        viewModel = ConversationsViewModel(getConversationsUseCase)

        viewModel.conversations.test {
            // Initial value is emptyList from stateIn
            skipItems(1)
            assertEquals(conversations, awaitItem())
        }
    }
}
