package dev.fahim.livescanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightFactsTest {

    private fun aircraft(
        onGround: Boolean = false,
        speed: Double = 250.0,
        vertical: Int? = 0,
        lat: Double = 35.4,
        lon: Double = -80.9,
    ) = Aircraft(
        hex = "a1b2c3", callsign = "DAL450", registration = "N123DL", type = "A321",
        lat = lat, lon = lon, altitudeFt = 8000, onGround = onGround,
        groundSpeedKt = speed, trackDeg = 176.0, squawk = "1234", emergency = false,
        verticalRateFpm = vertical, category = "A3", seenNanos = 0L,
    )

    // ── Runway, as heard ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a landing clearance yields the runway`() {
        assertEquals("18L", runwayFromTranscript("Delta four fifty cleared to land runway one eight left"))
        assertEquals("36C", runwayFromTranscript("cleared to land runway three six center"))
        assertEquals("23", runwayFromTranscript("taxi to runway two three"))
    }

    @Test
    fun `a single digit runway is zero padded to match the chart`() {
        // A controller says "runway five"; every chart and the runway database say "05".
        assertEquals("05", runwayFromTranscript("cleared for takeoff runway five"))
    }

    @Test
    fun `hyphenated speech still parses`() {
        assertEquals("18L", runwayFromTranscript("runway one-eight-left cleared to land"))
    }

    @Test
    fun `a transmission with no runway yields nothing`() {
        assertNull(runwayFromTranscript("Delta four fifty contact ground point niner"))
        assertNull(runwayFromTranscript(""))
    }

    @Test
    fun `the field's own runway list is what rejects a misread`() {
        // "cleared to land runway one eight, left turn after landing" reads as 18L. At a field
        // with no 18L that is nonsense, and the runway list is the only thing that can say so.
        val heard = runwayFromTranscript("cleared to land runway one eight, left turn at the end")
        assertEquals("18L", heard)

        val charlotte = listOf(RunwayEnd("18C", 35.2271, -80.9531, 176.0, 10000),
                               RunwayEnd("18L", 35.2244, -80.9361, 176.0, 8676))
        val singleRunwayField = listOf(RunwayEnd("18", 35.0, -80.0, 176.0, 5000))
        assertTrue(validRunway(heard, charlotte))
        assertFalse(validRunway(heard, singleRunwayField))
        assertFalse(validRunway(null, charlotte))
    }

    // ── Gate, as heard ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a gate spelled phonetically comes back as a gate`() {
        assertEquals("C15", gateFromTranscript("Delta four fifty taxi to gate charlie one five"))
        assertEquals("B12", gateFromTranscript("gate bravo twelve"))
        assertEquals("E31", gateFromTranscript("continue to gate echo thirty one"))
    }

    @Test
    fun `a bare numeric gate works at fields that use them`() {
        assertEquals("42", gateFromTranscript("taxi to gate forty two"))
        assertEquals("7", gateFromTranscript("gate seven"))
    }

    @Test
    fun `stand is accepted as well as gate`() {
        assertEquals("A9", gateFromTranscript("proceed to stand alpha nine"))
    }

    @Test
    fun `a transmission with no gate yields nothing`() {
        assertNull(gateFromTranscript("Delta four fifty turn left heading two seven zero"))
        assertNull(gateFromTranscript("contact ground"))
    }

    // ── Phase ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a stopped aircraft on the ground is at the gate, a moving one is taxiing`() {
        assertEquals(FlightPhase.PARKED, phaseOf(aircraft(onGround = true, speed = 0.0), 0.5))
        assertEquals(FlightPhase.TAXI, phaseOf(aircraft(onGround = true, speed = 14.0), 0.5))
    }

    @Test
    fun `descending near the field is an approach, descending far away is not`() {
        val descending = aircraft(vertical = -1200)
        assertEquals(FlightPhase.APPROACH, phaseOf(descending, 8.0))
        assertEquals(FlightPhase.DESCENT, phaseOf(descending, 60.0))
        // Without a distance we cannot claim it is on approach, only that it is coming down.
        assertEquals(FlightPhase.DESCENT, phaseOf(descending, null))
    }

    @Test
    fun `climbing near the field is a departure`() {
        assertEquals(FlightPhase.DEPARTURE, phaseOf(aircraft(vertical = 2200), 4.0))
        assertEquals(FlightPhase.CLIMB, phaseOf(aircraft(vertical = 2200), 55.0))
    }

    @Test
    fun `level flight is en route, and a missing vertical rate does not crash it`() {
        assertEquals(FlightPhase.CRUISE, phaseOf(aircraft(vertical = 0), 40.0))
        assertEquals(FlightPhase.CRUISE, phaseOf(aircraft(vertical = null), 40.0))
        // Small rates are turbulence, not a phase change.
        assertEquals(FlightPhase.CRUISE, phaseOf(aircraft(vertical = 120), 5.0))
    }

    // ── Direction ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `compass points round to the nearest of sixteen`() {
        assertEquals("N", compassPoint(0.0))
        assertEquals("N", compassPoint(359.0))
        assertEquals("NE", compassPoint(45.0))
        assertEquals("W", compassPoint(270.0))
        assertEquals("NW", compassPoint(315.0))
        assertEquals("N", compassPoint(-1.0))
    }

    @Test
    fun `bearing from the field reads as range and direction`() {
        // Due north of Charlotte, about 12 NM out.
        val north = bearingTo(35.214, -80.9431, aircraft(lat = 35.414, lon = -80.9431))
        assertEquals(0.0, north.bearingDeg, 0.5)
        assertEquals(12.0, north.distanceNm, 0.3)
        assertEquals("N", north.point)
        assertEquals("12 NM N", bearingPhrase(north))
    }

    @Test
    fun `an aircraft over the field reads as overhead rather than a random bearing`() {
        val overhead = bearingTo(35.214, -80.9431, aircraft(lat = 35.2145, lon = -80.9432))
        assertEquals("overhead", bearingPhrase(overhead))
    }

    // ── What the radio would call a contact ──────────────────────────────────────────────────

    @Test
    fun `a callsign is what the radio uses`() {
        assertEquals("DAL450", radioIdentOf(aircraft()))
    }

    @Test
    fun `without a callsign the registration stands in`() {
        val ga = aircraft().copy(callsign = null)
        assertEquals("N123DL", radioIdentOf(ga))
    }

    @Test
    fun `blank and whitespace callsigns fall through rather than being followed`() {
        // ADS-B pads callsigns, so a contact with no flight ID arrives as spaces, not null.
        assertEquals("N123DL", radioIdentOf(aircraft().copy(callsign = "   ")))
        assertEquals("DAL450", radioIdentOf(aircraft().copy(callsign = " dal450 ")))
    }

    @Test
    fun `a contact with no name at all cannot be followed`() {
        // Nothing for a controller to have said means nothing to match chatter against, which is
        // what stops the scope offering a FOLLOW key that would open an empty panel.
        val anonymous = aircraft().copy(callsign = null, registration = null)
        assertNull(radioIdentOf(anonymous))
        assertNull(radioIdentOf(aircraft().copy(callsign = "  ", registration = "")))
    }
}
