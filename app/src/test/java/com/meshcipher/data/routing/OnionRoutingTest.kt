package com.meshcipher.data.routing

import com.meshcipher.data.transport.OnionRoutingHeader
import com.meshcipher.data.transport.RoutingMode
import com.meshcipher.domain.model.MeshPeer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.state.IdentityKeyStore

/**
 * MD-04: Unit tests for onion build + peel round-trip.
 */
class OnionRoutingTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun makePeer(userId: String, deviceId: String) = MeshPeer(
        deviceId = deviceId,
        userId = userId,
        displayName = userId,
        bluetoothAddress = deviceId,
        rssi = -60,
        lastSeen = System.currentTimeMillis(),
        isContact = true
    )

    /** In-memory IdentityKeyStore backed by a map of (userId → IdentityKeyPair). */
    private class TestKeyStore(private val keys: Map<String, IdentityKeyPair>) : IdentityKeyStore {
        override fun getIdentityKeyPair(): IdentityKeyPair =
            keys.values.first()

        override fun getLocalRegistrationId(): Int = 1

        override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean = true

        override fun isTrustedIdentity(
            address: SignalProtocolAddress,
            identityKey: IdentityKey,
            direction: IdentityKeyStore.Direction
        ): Boolean = true

        override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
            keys[address.name]?.publicKey
    }

    private fun buildTestKeys(vararg userIds: String): Pair<Map<String, IdentityKeyPair>, TestKeyStore> {
        val keyMap = userIds.associateWith { IdentityKeyPair.generate() }
        return keyMap to TestKeyStore(keyMap)
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `single layer (direct) — build and peel terminal`() {
        val (keys, store) = buildTestKeys("dest")
        val destPeer = makePeer("dest", "device-dest")

        val onion = OnionBuilder.buildOnion(emptyList(), destPeer, store)
        assertNotNull("Should build onion with no relay hops", onion)

        val privateKey = keys["dest"]!!.privateKey.serialize()
        val peeled = OnionPeeler.peelLayer(onion!!, privateKey)

        assertTrue("Single-hop onion should be terminal", peeled.isTerminal)
        assertNull("Terminal layer should have no inner onion", peeled.innerOnion)
        assertNull("Terminal layer should have no next-hop device ID", peeled.nextHopDeviceId)
    }

    @Test
    fun `3-hop onion — sequential peel reveals terminal at destination`() {
        val (keys, store) = buildTestKeys("hop1", "hop2", "hop3", "dest")
        val hop1 = makePeer("hop1", "device-hop1")
        val hop2 = makePeer("hop2", "device-hop2")
        val hop3 = makePeer("hop3", "device-hop3")
        val dest = makePeer("dest", "device-dest")

        val outerOnion = OnionBuilder.buildOnion(listOf(hop1, hop2, hop3), dest, store)
        assertNotNull("Should build 3-hop onion", outerOnion)

        // Peel hop1's layer.
        val peeled1 = OnionPeeler.peelLayer(outerOnion!!, keys["hop1"]!!.privateKey.serialize())
        assertFalse("hop1 layer should not be terminal", peeled1.isTerminal)
        assertNotNull("hop1 peeled layer should have inner onion", peeled1.innerOnion)

        // Peel hop2's layer.
        val innerOnion1 = peeled1.innerOnion!!
        val peeled2 = OnionPeeler.peelLayer(innerOnion1, keys["hop2"]!!.privateKey.serialize())
        assertFalse("hop2 layer should not be terminal", peeled2.isTerminal)
        assertNotNull("hop2 peeled layer should have inner onion", peeled2.innerOnion)

        // Peel hop3's layer.
        val peeled3 = OnionPeeler.peelLayer(peeled2.innerOnion!!, keys["hop3"]!!.privateKey.serialize())
        assertFalse("hop3 layer should not be terminal", peeled3.isTerminal)
        assertNotNull("hop3 peeled layer should have inner onion", peeled3.innerOnion)

        // Peel destination layer.
        val peeledDest = OnionPeeler.peelLayer(peeled3.innerOnion!!, keys["dest"]!!.privateKey.serialize())
        assertTrue("Destination layer should be terminal", peeledDest.isTerminal)
        assertNull("Destination layer should have no inner onion", peeledDest.innerOnion)
    }

    @Test
    fun `each intermediate layer does not expose destination or full route`() {
        val (keys, store) = buildTestKeys("hop1", "hop2", "dest")
        val hop1 = makePeer("hop1", "device-hop1")
        val hop2 = makePeer("hop2", "device-hop2")
        val dest = makePeer("dest", "device-dest")

        val onion = OnionBuilder.buildOnion(listOf(hop1, hop2), dest, store)!!

        // Peeling hop1's layer reveals next hop (hop2 or dest) but NOT the destination userId.
        val peeled1 = OnionPeeler.peelLayer(onion, keys["hop1"]!!.privateKey.serialize())
        // nextHopDeviceId is a device ID (opaque), not the destination userId.
        assertFalse("Intermediate layer must not expose destination userId as nextHopDeviceId",
            peeled1.nextHopDeviceId == "dest")
        assertFalse("Intermediate layer must not expose destinationUserId",
            peeled1.nextHopDeviceId == dest.userId)
    }

    @Test
    fun `encrypted layer is exactly ONION_LAYER_SIZE bytes`() {
        val (_, store) = buildTestKeys("dest")
        val dest = makePeer("dest", "device-dest")

        val onion = OnionBuilder.buildOnion(emptyList(), dest, store)!!
        assertEquals(
            "encryptedLayer must be exactly ${OnionRoutingHeader.ONION_LAYER_SIZE} bytes",
            OnionRoutingHeader.ONION_LAYER_SIZE,
            onion.encryptedLayer.size
        )
    }

    @Test
    fun `3-hop onion — all encrypted layers are ONION_LAYER_SIZE bytes`() {
        val (keys, store) = buildTestKeys("hop1", "hop2", "dest")
        val hop1 = makePeer("hop1", "device-hop1")
        val hop2 = makePeer("hop2", "device-hop2")
        val dest = makePeer("dest", "device-dest")

        val onion = OnionBuilder.buildOnion(listOf(hop1, hop2), dest, store)!!
        assertEquals(OnionRoutingHeader.ONION_LAYER_SIZE, onion.encryptedLayer.size)

        val peeled1 = OnionPeeler.peelLayer(onion, keys["hop1"]!!.privateKey.serialize())
        val innerOnion1 = peeled1.innerOnion!!
        assertEquals(OnionRoutingHeader.ONION_LAYER_SIZE, innerOnion1.encryptedLayer.size)

        val peeled2 = OnionPeeler.peelLayer(innerOnion1, keys["hop2"]!!.privateKey.serialize())
        val innerOnion2 = peeled2.innerOnion!!
        assertEquals(OnionRoutingHeader.ONION_LAYER_SIZE, innerOnion2.encryptedLayer.size)
    }

    @Test
    fun `missing identity key returns null (fallback to plaintext)`() {
        val (_, store) = buildTestKeys("only-dest")
        // Route peer "hop1" has no key in the store.
        val hop1 = makePeer("hop1-no-key", "device-hop1")
        val dest = makePeer("only-dest", "device-dest")

        val onion = OnionBuilder.buildOnion(listOf(hop1), dest, store)
        assertNull("Should return null when a hop key is missing", onion)
    }

    @Test
    fun `HKDF produces 32-byte key`() {
        val ikm = ByteArray(32) { it.toByte() }
        val info = "test-info".toByteArray()
        val key = OnionBuilder.hkdfDerive(ikm, info, 32)
        assertEquals(32, key.size)
    }
}
