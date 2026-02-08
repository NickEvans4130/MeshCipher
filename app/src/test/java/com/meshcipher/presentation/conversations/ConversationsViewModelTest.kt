package com.meshcipher.presentation.conversations

import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.data.relay.WebSocketManager
import com.meshcipher.data.relay.WebSocketState
import com.meshcipher.domain.model.Conversation
import com.meshcipher.domain.usecase.GetConversationsUseCase
import com.meshcipher.domain.usecase.ReceiveMessageUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModelTest {

    private lateinit var getConversationsUseCase: GetConversationsUseCase
    private lateinit var receiveMessageUseCase: ReceiveMessageUseCase
    private lateinit var appPreferences: AppPreferences
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var viewModel: ConversationsViewModel

    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(testScheduler)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        getConversationsUseCase = mockk()
        receiveMessageUseCase = mockk()
        appPreferences = mockk()
        webSocketManager = mockk()
        every { appPreferences.connectionMode } returns flowOf("DIRECT")
        every { appPreferences.userId } returns flowOf(null)
        every { webSocketManager.connectionState } returns MutableStateFlow(WebSocketState.DISCONNECTED)
        coEvery { receiveMessageUseCase(any()) } returns Result.success(0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `conversations flow emits data from use case`() {
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

        viewModel = ConversationsViewModel(getConversationsUseCase, receiveMessageUseCase, appPreferences, webSocketManager)

        // Start a subscriber to activate WhileSubscribed stateIn
        var result: List<Conversation> = emptyList()
        val scope = TestScope(testDispatcher)
        val job = scope.launch {
            viewModel.conversations.collect { result = it }
        }

        // Advance to let the flow emit
        testScheduler.advanceTimeBy(1000)
        testScheduler.runCurrent()

        assertEquals(conversations, result)
        job.cancel()
    }
}
