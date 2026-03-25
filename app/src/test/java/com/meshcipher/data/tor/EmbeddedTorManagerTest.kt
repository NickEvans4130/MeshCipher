package com.meshcipher.data.tor

import android.content.Context
import com.meshcipher.data.local.preferences.AppPreferences
import com.meshcipher.data.tor.TorBridgeRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Proxy

class EmbeddedTorManagerTest {

    private lateinit var context: Context
    private lateinit var appPreferences: AppPreferences
    private lateinit var bridgeRepository: TorBridgeRepository
    private lateinit var embeddedTorManager: EmbeddedTorManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        appPreferences = mockk(relaxed = true)
        bridgeRepository = mockk(relaxed = true)
        val filesDir = File(System.getProperty("java.io.tmpdir"), "tor_test")
        filesDir.mkdirs()
        every { context.filesDir } returns filesDir
        every { appPreferences.ephemeralOnionMode } returns flowOf(false)
        every { bridgeRepository.bridges } returns MutableStateFlow(emptyList())
        embeddedTorManager = EmbeddedTorManager(context, appPreferences, bridgeRepository)
    }

    @Test
    fun `initial status is STOPPED`() {
        val status = embeddedTorManager.status.value

        assertEquals(EmbeddedTorManager.State.STOPPED, status.state)
        assertEquals(0, status.bootstrapPercent)
        assertNull(status.onionAddress)
        assertEquals(0, status.socksPort)
        assertNull(status.errorMessage)
    }

    @Test
    fun `getSocksProxy returns SOCKS proxy`() {
        val proxy = embeddedTorManager.getSocksProxy()

        assertEquals(Proxy.Type.SOCKS, proxy.type())
        val address = proxy.address() as java.net.InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
    }

    @Test
    fun `getSocksProxy uses default port when not running`() {
        val proxy = embeddedTorManager.getSocksProxy()

        val address = proxy.address() as java.net.InetSocketAddress
        assertEquals(9050, address.port)
    }

    @Test
    fun `getOnionAddress returns null when not running`() {
        assertNull(embeddedTorManager.getOnionAddress())
    }

    @Test
    fun `isRunning returns false when stopped`() {
        assertFalse(embeddedTorManager.isRunning())
    }

    @Test
    fun `EmbeddedTorStatus default values`() {
        val status = EmbeddedTorManager.EmbeddedTorStatus()

        assertEquals(EmbeddedTorManager.State.STOPPED, status.state)
        assertEquals(0, status.bootstrapPercent)
        assertNull(status.onionAddress)
        assertEquals(0, status.socksPort)
        assertNull(status.errorMessage)
    }

    @Test
    fun `EmbeddedTorStatus copy with updated fields`() {
        val status = EmbeddedTorManager.EmbeddedTorStatus(
            state = EmbeddedTorManager.State.RUNNING,
            bootstrapPercent = 100,
            onionAddress = "test123.onion",
            socksPort = 9150
        )

        assertEquals(EmbeddedTorManager.State.RUNNING, status.state)
        assertEquals(100, status.bootstrapPercent)
        assertEquals("test123.onion", status.onionAddress)
        assertEquals(9150, status.socksPort)
    }

    @Test
    fun `State enum has all expected values`() {
        val states = EmbeddedTorManager.State.entries
        assertEquals(6, states.size)
        assertTrue(states.contains(EmbeddedTorManager.State.STOPPED))
        assertTrue(states.contains(EmbeddedTorManager.State.STARTING))
        assertTrue(states.contains(EmbeddedTorManager.State.BOOTSTRAPPING))
        assertTrue(states.contains(EmbeddedTorManager.State.CREATING_HIDDEN_SERVICE))
        assertTrue(states.contains(EmbeddedTorManager.State.RUNNING))
        assertTrue(states.contains(EmbeddedTorManager.State.ERROR))
    }
}
