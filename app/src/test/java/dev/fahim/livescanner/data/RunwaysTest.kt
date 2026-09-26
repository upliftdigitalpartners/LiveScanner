package dev.fahim.livescanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Runway matching against Charlotte's real geometry, straight out of runways.json.
 *
 * Charlotte is the hard case on purpose: three parallels about a mile apart, each usable from
 * both ends, so a match that leans on position alone gets it wrong most of the time.
 */
class RunwaysTest {

    private val kclt = listOf(
        RunwayEnd("18C", 35.227100, -80.953102, 176.0, 10000),
        RunwayEnd("36C", 35.200298, -80.950798, 356.0, 10000),
        RunwayEnd("18R", 35.224998, -80.967400, 176.0, 9000),
        RunwayEnd("36L", 35.200901, -80.965302, 356.0, 9000),
        RunwayEnd("18L", 35.224400, -80.936096, 176.0, 8676),
        RunwayEnd("36R", 35.201199, -80.934097, 356.0, 8676),
    )

    /** Places an aircraft [milesOut] before a threshold, [offsetNm] right of the centreline. */
    private fun onFinalTo(end: RunwayEnd, milesOut: Double, offsetNm: Double = 0.0): Pair<Double, Double> {
        val heading = Math.toRadians(end.headingDeg)
        // Back down the centreline, then sideways along the perpendicular.
        val north = -milesOut * cos(heading) - offsetNm * sin(heading)
        val east = -milesOut * sin(heading) + offsetNm * cos(heading)
        return Pair(
            end.lat + north / 60.0,
            end.lon + east / (60.0 * cos(Math.toRadians(end.lat))),
        )
    }

    @Test
    fun `an aircraft on final to 18C gets 18C, not the parallels`() {
        val (lat, lon) = onFinalTo(kclt[0], milesOut = 3.0)
        assertEquals("18C", matchRunway(kclt, lat, lon, 176.0)?.ident)
    }

    @Test
    fun `heading decides between the two ends of one strip`() {
        // 18C and 36C are the same concrete. Position cannot tell them apart; direction can.
        val (lat, lon) = onFinalTo(kclt[1], milesOut = 3.0)
        assertEquals("36C", matchRunway(kclt, lat, lon, 356.0)?.ident)
        // Same spot, flying the other way: now it is lined up with nothing sensible nearby.
        assertTrue(matchRunway(kclt, lat, lon, 176.0)?.ident != "36C")
    }

    @Test
    fun `each of the three parallels is picked out from the other two`() {
        for (end in kclt) {
            val (lat, lon) = onFinalTo(end, milesOut = 4.0)
            assertEquals(end.ident, matchRunway(kclt, lat, lon, end.headingDeg)?.ident)
        }
    }

    @Test
    fun `a hardcoded position on final to 18C matches, with no shared arithmetic`() {
        // Independently computed: 3 NM north of the 18C threshold on the extended centreline.
        // If the placement helper and the matcher ever agree on the same sign error, this fails.
        assertEquals("18C", matchRunway(kclt, 35.27698, -80.957367, 176.0)?.ident)
    }

    @Test
    fun `an aircraft well off the centreline is not on a runway`() {
        val (lat, lon) = onFinalTo(kclt[0], milesOut = 3.0, offsetNm = 3.0)
        assertNull(matchRunway(kclt, lat, lon, 176.0))
    }

    @Test
    fun `an aircraft crossing the field at right angles is not landing`() {
        val (lat, lon) = onFinalTo(kclt[0], milesOut = 2.0)
        assertNull(matchRunway(kclt, lat, lon, 86.0))
    }

    @Test
    fun `an aircraft far out on the centreline is not claimed yet`() {
        val (lat, lon) = onFinalTo(kclt[0], milesOut = 25.0)
        assertNull(matchRunway(kclt, lat, lon, 176.0))
    }

    @Test
    fun `an aircraft rolled out past the threshold still counts as on the runway`() {
        val (lat, lon) = onFinalTo(kclt[0], milesOut = -1.0)
        assertEquals("18C", matchRunway(kclt, lat, lon, 176.0)?.ident)
    }

    @Test
    fun `no runways means no match rather than a crash`() {
        assertNull(matchRunway(emptyList(), 35.2, -80.9, 176.0))
    }

    @Test
    fun `angleBetween wraps around north`() {
        assertEquals(0.0, angleBetween(176.0, 176.0), 1e-9)
        assertEquals(10.0, angleBetween(355.0, 5.0), 1e-9)
        assertEquals(180.0, angleBetween(176.0, 356.0), 1e-9)
        assertEquals(90.0, angleBetween(0.0, 270.0), 1e-9)
    }
}
