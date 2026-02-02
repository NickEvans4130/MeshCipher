package com.meshcipher.data.transport

import com.meshcipher.data.remote.api.RelayApiService
import com.meshcipher.data.remote.dto.HealthResponse
import com.meshcipher.data.remote.dto.QueuedMessagesResponse
import com.meshcipher.data.remote.dto.RelayMessageResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class InternetTransportTest {

    private lateinit var relayApiService: RelayApiService
    private lateinit var internetTransport: InternetTransport

    @Before
    fun setup() {
        relayApiService = mockk()
        internetTransport = InternetTransport(relayApiService)
    }

    @Test
    fun `receiveMessages returns empty list when no messages`() = runTest {
        val apiResponse = QueuedMessagesResponse(
            messages = emptyList(),
            count = 0
        )

        coEvery { relayApiService.getMessages(any()) } returns Response.success(apiResponse)

        val result = internetTransport.receiveMessages("recipient-1")

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull()?.size)
    }

    @Test
    fun `receiveMessages returns failure on exception`() = runTest {
        coEvery { relayApiService.getMessages(any()) } throws Exception("Network error")

        val result = internetTransport.receiveMessages("recipient-1")

        assertTrue(result.isFailure)
    }

    @Test
    fun `isServerReachable returns true when healthy`() = runTest {
        val healthResponse = HealthResponse(
            status = "healthy",
            database = "connected",
            queuedMessages = 0,
            registeredDevices = 0,
            timestamp = "2024-01-01T00:00:00Z"
        )

        coEvery { relayApiService.healthCheck() } returns Response.success(healthResponse)

        val result = internetTransport.isServerReachable()

        assertTrue(result)
    }

    @Test
    fun `isServerReachable returns false on exception`() = runTest {
        coEvery { relayApiService.healthCheck() } throws Exception("Connection refused")

        val result = internetTransport.isServerReachable()

        assertFalse(result)
    }
}
