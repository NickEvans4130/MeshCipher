package com.meshcipher.data.tor

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.Proxy

class TorManagerTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var torManager: TorManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        packageManager = mockk()
        every { context.packageManager } returns packageManager
        torManager = TorManager(context)
    }

    @Test
    fun `isOrbotInstalled returns true when Orbot package exists`() {
        every {
            packageManager.getPackageInfo(TorManager.ORBOT_PACKAGE, 0)
        } returns PackageInfo()

        assertTrue(torManager.isOrbotInstalled())
    }

    @Test
    fun `isOrbotInstalled returns false when Orbot package not found`() {
        every {
            packageManager.getPackageInfo(TorManager.ORBOT_PACKAGE, 0)
        } throws PackageManager.NameNotFoundException()

        assertFalse(torManager.isOrbotInstalled())
    }

    @Test
    fun `createTorProxy returns SOCKS proxy with correct address`() {
        val proxy = torManager.createTorProxy()

        assertEquals(Proxy.Type.SOCKS, proxy.type())
        val address = proxy.address() as java.net.InetSocketAddress
        assertEquals(TorManager.SOCKS_HOST, address.hostString)
        assertEquals(TorManager.SOCKS_PORT, address.port)
    }

    @Test
    fun `initial status has all fields false`() {
        val status = torManager.status.value

        assertFalse(status.orbotInstalled)
        assertFalse(status.orbotRunning)
        assertFalse(status.proxyReady)
    }

    @Test
    fun `refreshStatus updates installed status`() {
        every {
            packageManager.getPackageInfo(TorManager.ORBOT_PACKAGE, 0)
        } returns PackageInfo()

        torManager.refreshStatus()

        assertTrue(torManager.status.value.orbotInstalled)
    }

    @Test
    fun `refreshStatus with uninstalled Orbot sets running to false`() {
        every {
            packageManager.getPackageInfo(TorManager.ORBOT_PACKAGE, 0)
        } throws PackageManager.NameNotFoundException()

        torManager.refreshStatus()

        assertFalse(torManager.status.value.orbotInstalled)
        assertFalse(torManager.status.value.orbotRunning)
        assertFalse(torManager.status.value.proxyReady)
    }

    @Test
    fun `getOrbotInstallIntent has correct action`() {
        val intent = torManager.getOrbotInstallIntent()

        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
    }
}
