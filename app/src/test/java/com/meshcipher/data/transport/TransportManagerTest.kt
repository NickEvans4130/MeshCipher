package com.meshcipher.data.transport

import com.google.gson.Gson
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.data.tor.TorManager
import com.meshcipher.domain.model.ConnectionMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class TransportManagerTest {

    private lateinit var directTransport: InternetTransport
    private lateinit var appPreferences: AppPreferences
    private lateinit var torManager: TorManager
    private lateinit var gson: Gson
    private lateinit var retrofit: Retrofit
    private lateinit var transportManager: TransportManager

    @Before
    fun setup() {
        directTransport = mockk()
        appPreferences = mockk()
        torManager = mockk()
        gson = Gson()
        retrofit = mockk(relaxed = true)

        every { appPreferences.connectionMode } returns flowOf(ConnectionMode.DIRECT.name)

        transportManager = TransportManager(
            directTransport = directTransport,
            appPreferences = appPreferences,
            torManager = torManager,
            gson = gson,
            retrofit = retrofit
        )
    }

    @Test
    fun `getActiveTransport returns direct transport by default`() {
        val transport = transportManager.getActiveTransport()

        assertSame(directTransport, transport)
    }

    @Test
    fun `getConnectionMode returns DIRECT by default`() {
        assertEquals(ConnectionMode.DIRECT, transportManager.getConnectionMode())
    }

    @Test
    fun `clearTorTransport does not throw`() {
        transportManager.clearTorTransport()
    }
}
