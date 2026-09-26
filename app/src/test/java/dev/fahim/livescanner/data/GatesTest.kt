package dev.fahim.livescanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatesTest {

    /** A row of gates 60 m apart along a terminal face, which is roughly how they really sit. */
    private val concourse = (0..5).map { i ->
        Gate("B${10 + i}", lat = 35.2140, lon = -80.9431 + i * 60.0 / (111_320.0 * 0.8168))
    }

    private fun near(gate: Gate, metersNorth: Double = 0.0, metersEast: Double = 0.0) =
        Pair(
            gate.lat + metersNorth / 111_320.0,
            gate.lon + metersEast / (111_320.0 * 0.8168),
        )

    @Test
    fun `a metre scale that matches the real world`() {
        // One degree of latitude is about 111 km; a tenth of a degree is about 11 km.
        assertEquals(11_132.0, metersBetween(35.0, -80.0, 35.1, -80.0), 5.0)
        assertEquals(0.0, metersBetween(35.0, -80.0, 35.0, -80.0), 1e-9)
    }

    @Test
    fun `an aircraft parked on a stand gets that gate`() {
        val (lat, lon) = near(concourse[2], metersNorth = 25.0)
        assertEquals("B12", nearestGate(concourse, lat, lon)?.ref)
    }

    @Test
    fun `sitting between two gates names neither`() {
        // Halfway along the 60 m between B12 and B13: both are equally plausible, so the honest
        // answer is nothing at all. Naming the wrong gate is worse than naming none.
        val midLon = (concourse[2].lon + concourse[3].lon) / 2
        assertNull(nearestGate(concourse, concourse[2].lat, midLon))
    }

    @Test
    fun `an aircraft out on the taxiway is not at a gate`() {
        val (lat, lon) = near(concourse[2], metersNorth = 400.0)
        assertNull(nearestGate(concourse, lat, lon))
    }

    @Test
    fun `a single mapped gate still needs the aircraft to be close to it`() {
        val only = listOf(concourse[0])
        val (atIt, atItLon) = near(only[0], metersNorth = 20.0)
        assertEquals("B10", nearestGate(only, atIt, atItLon)?.ref)

        val (away, awayLon) = near(only[0], metersNorth = 300.0)
        assertNull(nearestGate(only, away, awayLon))
    }

    @Test
    fun `no gate data means no gate rather than a crash`() {
        assertNull(nearestGate(emptyList(), 35.214, -80.9431))
    }

    @Test
    fun `every gate in a concourse is reachable from its own stand`() {
        for (gate in concourse) {
            val (lat, lon) = near(gate, metersNorth = 20.0)
            assertEquals(gate.ref, nearestGate(concourse, lat, lon)?.ref)
        }
    }

    @Test
    fun `gates parse from the asset shape the builder writes`() {
        val json = """
            {"airports":{"KCLT":[
              {"ref":"B12","lat":35.214,"lon":-80.9431,"terminal":"B"},
              {"ref":"E15","lat":35.2145,"lon":-80.9425}
            ]}}
        """.trimIndent()
        val parsed = AppJson.decodeFromString(GateFile.serializer(), json).airports.getValue("KCLT")
        assertEquals(2, parsed.size)
        assertEquals("B12", parsed[0].ref)
        assertEquals("B", parsed[0].terminal)
        // terminal is optional in the builder's output and must stay optional here.
        assertNull(parsed[1].terminal)
        assertTrue(parsed.all { it.lat != 0.0 && it.lon != 0.0 })
    }
}
