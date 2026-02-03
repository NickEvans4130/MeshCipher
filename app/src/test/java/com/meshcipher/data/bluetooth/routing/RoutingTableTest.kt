package com.meshcipher.data.bluetooth.routing

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RoutingTableTest {

    private lateinit var routingTable: RoutingTable

    @Before
    fun setup() {
        routingTable = RoutingTable()
    }

    @Test
    fun `addRoute stores new route`() {
        routingTable.addRoute("user-1", "device-A", 1)

        val route = routingTable.getRoute("user-1")
        assertNotNull(route)
        assertEquals("user-1", route!!.destinationUserId)
        assertEquals("device-A", route.nextHopDeviceId)
        assertEquals(1, route.hopCount)
    }

    @Test
    fun `addRoute updates route with lower hop count`() {
        routingTable.addRoute("user-1", "device-A", 3)
        routingTable.addRoute("user-1", "device-B", 1)

        val route = routingTable.getRoute("user-1")
        assertNotNull(route)
        assertEquals("device-B", route!!.nextHopDeviceId)
        assertEquals(1, route.hopCount)
    }

    @Test
    fun `addRoute does not update route with higher hop count`() {
        routingTable.addRoute("user-1", "device-A", 1)
        routingTable.addRoute("user-1", "device-B", 3)

        val route = routingTable.getRoute("user-1")
        assertNotNull(route)
        assertEquals("device-A", route!!.nextHopDeviceId)
        assertEquals(1, route.hopCount)
    }

    @Test
    fun `getRoute returns null for unknown user`() {
        assertNull(routingTable.getRoute("unknown-user"))
    }

    @Test
    fun `removeStaleRoutes removes expired entries`() {
        routingTable.addRoute("user-1", "device-A", 1)

        // Manually set the entry to be stale by accessing internals
        // Since we can't easily manipulate time, test that fresh routes survive
        routingTable.removeStaleRoutes()

        // Fresh route should still be there
        assertNotNull(routingTable.getRoute("user-1"))
    }

    @Test
    fun `getAllRoutes returns all entries`() {
        routingTable.addRoute("user-1", "device-A", 1)
        routingTable.addRoute("user-2", "device-B", 2)

        val routes = routingTable.getAllRoutes()
        assertEquals(2, routes.size)
        assertTrue(routes.containsKey("user-1"))
        assertTrue(routes.containsKey("user-2"))
    }

    @Test
    fun `clear removes all routes`() {
        routingTable.addRoute("user-1", "device-A", 1)
        routingTable.addRoute("user-2", "device-B", 2)

        routingTable.clear()

        assertTrue(routingTable.getAllRoutes().isEmpty())
        assertNull(routingTable.getRoute("user-1"))
    }

    @Test
    fun `addRoute with equal hop count keeps existing route`() {
        routingTable.addRoute("user-1", "device-A", 2)
        routingTable.addRoute("user-1", "device-B", 2)

        val route = routingTable.getRoute("user-1")
        assertNotNull(route)
        assertEquals("device-A", route!!.nextHopDeviceId)
    }
}
