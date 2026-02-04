package com.meshcipher.data.bluetooth.routing

import com.meshcipher.data.bluetooth.BluetoothMeshManager
import com.meshcipher.data.bluetooth.GattClientManager
import com.meshcipher.data.identity.IdentityManager
import com.meshcipher.domain.model.Identity
import com.meshcipher.domain.model.MeshMessage
import com.meshcipher.domain.model.MeshPeer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MeshRouterTest {

    private lateinit var bluetoothMeshManager: BluetoothMeshManager
    private lateinit var gattClientManager: GattClientManager
    private lateinit var identityManager: IdentityManager
    private lateinit var meshRouter: MeshRouter

    private val peersFlow = MutableStateFlow<List<MeshPeer>>(emptyList())

    @Before
    fun setup() {
        bluetoothMeshManager = mockk()
        gattClientManager = mockk()
        identityManager = mockk()

        every { bluetoothMeshManager.discoveredPeers } returns peersFlow

        coEvery { identityManager.getIdentity() } returns Identity(
            userId = "my-user",
            hardwarePublicKey = ByteArray(32),
            createdAt = System.currentTimeMillis(),
            deviceId = "my-device",
            deviceName = "Test Device"
        )

        meshRouter = MeshRouter(bluetoothMeshManager, gattClientManager, identityManager)
    }

    @Test
    fun `routeMessage drops duplicate messages`() = runTest {
        val message = createTestMessage("msg-1")

        // First call succeeds (floods to empty neighbors list, which fails)
        meshRouter.routeMessage(message)

        // Second call with same ID should be dropped
        val result = meshRouter.routeMessage(message)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `routeMessage fails when TTL expired`() = runTest {
        val message = createTestMessage("msg-ttl", hopCount = 5, ttl = 5)

        val result = meshRouter.routeMessage(message)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("TTL") == true)
    }

    @Test
    fun `routeMessage fails with no neighbors`() = runTest {
        peersFlow.value = emptyList()

        val message = createTestMessage("msg-no-neighbors")

        val result = meshRouter.routeMessage(message)
        assertTrue(result.isFailure)
    }

    @Test
    fun `routeMessage floods to neighbors when no route exists`() = runTest {
        val neighbor = MeshPeer(
            deviceId = "neighbor-1",
            userId = "neighbor-user",
            displayName = null,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            rssi = -60,
            lastSeen = System.currentTimeMillis(),
            isContact = false,
            hopCount = 1
        )
        peersFlow.value = listOf(neighbor)

        coEvery { gattClientManager.sendMessage(any(), any()) } returns Result.success(Unit)

        val message = createTestMessage("msg-flood")
        val result = meshRouter.routeMessage(message)

        assertTrue(result.isSuccess)
        coVerify { gattClientManager.sendMessage("AA:BB:CC:DD:EE:FF", any()) }
    }

    @Test
    fun `routeMessage uses routing table when route exists`() = runTest {
        val neighbor = MeshPeer(
            deviceId = "relay-device",
            userId = "relay-user",
            displayName = null,
            bluetoothAddress = "11:22:33:44:55:66",
            rssi = -50,
            lastSeen = System.currentTimeMillis(),
            isContact = false,
            hopCount = 1
        )
        peersFlow.value = listOf(neighbor)

        meshRouter.updateRoute("target-user", "relay-device", 2)

        coEvery { gattClientManager.sendMessage(any(), any()) } returns Result.success(Unit)

        val message = createTestMessage("msg-routed", destinationUserId = "target-user")
        val result = meshRouter.routeMessage(message)

        assertTrue(result.isSuccess)
        coVerify { gattClientManager.sendMessage("11:22:33:44:55:66", any()) }
    }

    @Test
    fun `routeMessage skips origin device in flood`() = runTest {
        val originPeer = MeshPeer(
            deviceId = "origin-device",
            userId = "origin-user",
            displayName = null,
            bluetoothAddress = "AA:AA:AA:AA:AA:AA",
            rssi = -60,
            lastSeen = System.currentTimeMillis(),
            isContact = false,
            hopCount = 1
        )
        val otherPeer = MeshPeer(
            deviceId = "other-device",
            userId = "other-user",
            displayName = null,
            bluetoothAddress = "BB:BB:BB:BB:BB:BB",
            rssi = -60,
            lastSeen = System.currentTimeMillis(),
            isContact = false,
            hopCount = 1
        )
        peersFlow.value = listOf(originPeer, otherPeer)

        coEvery { gattClientManager.sendMessage(any(), any()) } returns Result.success(Unit)

        val message = createTestMessage(
            "msg-skip-origin",
            originDeviceId = "origin-device"
        )
        meshRouter.routeMessage(message)

        // Should only send to otherPeer, not originPeer
        coVerify(exactly = 1) { gattClientManager.sendMessage("BB:BB:BB:BB:BB:BB", any()) }
        coVerify(exactly = 0) { gattClientManager.sendMessage("AA:AA:AA:AA:AA:AA", any()) }
    }

    @Test
    fun `canReach returns true for known route`() {
        meshRouter.updateRoute("user-1", "device-A", 1)
        assertTrue(meshRouter.canReach("user-1"))
    }

    @Test
    fun `canReach returns false for unknown user`() {
        assertFalse(meshRouter.canReach("unknown-user"))
    }

    @Test
    fun `handleIncomingMessage updates routing table`() {
        val message = MeshMessage(
            id = "incoming-1",
            originDeviceId = "remote-device",
            originUserId = "remote-user",
            destinationUserId = "my-user",
            encryptedPayload = ByteArray(10),
            timestamp = System.currentTimeMillis(),
            hopCount = 2,
            path = listOf("remote-device", "relay-device")
        )

        meshRouter.handleIncomingMessage(message, "relay-device")

        val route = meshRouter.routingTable.getRoute("remote-device")
        assertNotNull(route)
        assertEquals("relay-device", route!!.nextHopDeviceId)
    }

    private fun createTestMessage(
        id: String,
        originDeviceId: String = "sender-device",
        originUserId: String = "sender-user",
        destinationUserId: String = "recipient-user",
        hopCount: Int = 0,
        ttl: Int = 5
    ): MeshMessage = MeshMessage(
        id = id,
        originDeviceId = originDeviceId,
        originUserId = originUserId,
        destinationUserId = destinationUserId,
        encryptedPayload = "test-payload".toByteArray(),
        timestamp = System.currentTimeMillis(),
        ttl = ttl,
        hopCount = hopCount
    )
}
