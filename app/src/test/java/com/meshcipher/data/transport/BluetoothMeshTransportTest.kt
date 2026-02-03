package com.meshcipher.data.transport

import com.meshcipher.data.bluetooth.BluetoothMeshManager
import com.meshcipher.data.bluetooth.GattServerManager
import com.meshcipher.data.bluetooth.routing.MeshRouter
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.domain.model.Identity
import com.meshcipher.domain.model.MeshPeer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BluetoothMeshTransportTest {

    private lateinit var bluetoothMeshManager: BluetoothMeshManager
    private lateinit var gattServerManager: GattServerManager
    private lateinit var meshRouter: MeshRouter
    private lateinit var identityManager: IdentityManager
    private lateinit var transport: BluetoothMeshTransport

    private val peersFlow = MutableStateFlow<List<MeshPeer>>(emptyList())

    @Before
    fun setup() {
        bluetoothMeshManager = mockk()
        gattServerManager = mockk(relaxed = true)
        meshRouter = mockk()
        identityManager = mockk()

        every { bluetoothMeshManager.discoveredPeers } returns peersFlow
        every { bluetoothMeshManager.isBluetoothEnabled() } returns true

        coEvery { identityManager.getIdentity() } returns Identity(
            userId = "my-user",
            hardwarePublicKey = ByteArray(32),
            createdAt = System.currentTimeMillis(),
            deviceId = "my-device",
            deviceName = "Test"
        )

        transport = BluetoothMeshTransport(
            bluetoothMeshManager,
            gattServerManager,
            meshRouter,
            identityManager
        )
    }

    @Test
    fun `isAvailable returns false when no peers`() {
        peersFlow.value = emptyList()
        assertFalse(transport.isAvailable())
    }

    @Test
    fun `isAvailable returns true when peers exist and BLE enabled`() {
        peersFlow.value = listOf(
            MeshPeer(
                deviceId = "peer-1",
                userId = "user-1",
                displayName = null,
                bluetoothAddress = "AA:BB:CC:DD:EE:FF",
                rssi = -60,
                lastSeen = System.currentTimeMillis(),
                isContact = false,
                hopCount = 1
            )
        )
        assertTrue(transport.isAvailable())
    }

    @Test
    fun `isAvailable returns false when bluetooth disabled`() {
        every { bluetoothMeshManager.isBluetoothEnabled() } returns false
        peersFlow.value = listOf(
            MeshPeer(
                deviceId = "peer-1",
                userId = "user-1",
                displayName = null,
                bluetoothAddress = "AA:BB:CC:DD:EE:FF",
                rssi = -60,
                lastSeen = System.currentTimeMillis(),
                isContact = false,
                hopCount = 1
            )
        )
        assertFalse(transport.isAvailable())
    }

    @Test
    fun `sendMessage fails when not available`() = runTest {
        peersFlow.value = emptyList()

        val result = transport.sendMessage("recipient", "content".toByteArray())
        assertTrue(result.isFailure)
    }

    @Test
    fun `sendMessage succeeds when mesh routes message`() = runTest {
        peersFlow.value = listOf(
            MeshPeer(
                deviceId = "peer-1",
                userId = "user-1",
                displayName = null,
                bluetoothAddress = "AA:BB:CC:DD:EE:FF",
                rssi = -60,
                lastSeen = System.currentTimeMillis(),
                isContact = false,
                hopCount = 1
            )
        )

        coEvery { meshRouter.routeMessage(any()) } returns Result.success(Unit)

        val result = transport.sendMessage("recipient", "content".toByteArray())
        assertTrue(result.isSuccess)
        assertNotNull(result.getOrNull())
    }

    @Test
    fun `isRecipientReachable delegates to meshRouter`() {
        every { meshRouter.canReach("user-1") } returns true
        every { meshRouter.canReach("user-2") } returns false

        assertTrue(transport.isRecipientReachable("user-1"))
        assertFalse(transport.isRecipientReachable("user-2"))
    }
}
