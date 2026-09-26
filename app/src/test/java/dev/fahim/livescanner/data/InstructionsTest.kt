package dev.fahim.livescanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionsTest {

    private fun aircraft(
        altitude: Int? = 8000,
        track: Double = 176.0,
        speed: Double = 250.0,
    ) = Aircraft(
        hex = "a1b2c3", callsign = "DAL450", registration = null, type = "A321",
        lat = 35.4, lon = -80.9, altitudeFt = altitude, onGround = false,
        groundSpeedKt = speed, trackDeg = track, squawk = null, emergency = false,
        verticalRateFpm = -1200, category = "A3", seenNanos = 0L,
    )

    private fun one(kind: InstructionKind, text: String): Instruction? =
        instructionsFrom(text).firstOrNull { it.kind == kind }

    // ── Altitude ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `thousands are read as feet`() {
        assertEquals("5000", one(InstructionKind.ALTITUDE, "descend and maintain five thousand")?.value)
        assertEquals("3000", one(InstructionKind.ALTITUDE, "climb and maintain three thousand")?.value)
        assertEquals("10000", one(InstructionKind.ALTITUDE, "descend and maintain one zero thousand")?.value)
    }

    @Test
    fun `thousands and hundreds add up`() {
        val i = one(InstructionKind.ALTITUDE, "climb and maintain two thousand five hundred")
        assertEquals("2500", i?.value)
        assertEquals(2500.0, i?.target)
    }

    @Test
    fun `a flight level is a hundred feet a unit`() {
        val i = one(InstructionKind.ALTITUDE, "maintain flight level three five zero")
        assertEquals("FL350", i?.value)
        assertEquals(35_000.0, i?.target)
    }

    @Test
    fun `an altitude about someone else is not an instruction`() {
        // Traffic calls name an altitude without telling this aircraft to go there. Reading them
        // as instructions would have the panel claim a clearance that was never given.
        assertNull(one(InstructionKind.ALTITUDE, "traffic eleven o'clock five miles at seven thousand"))
    }

    @Test
    fun `pilot's discretion still carries the altitude`() {
        assertEquals("6000", one(InstructionKind.ALTITUDE, "descend at pilot's discretion to six thousand")?.value)
    }

    // ── Heading ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `headings keep their turn direction and three digits`() {
        assertEquals("270 L", one(InstructionKind.HEADING, "turn left heading two seven zero")?.value)
        assertEquals("090 R", one(InstructionKind.HEADING, "turn right heading zero nine zero")?.value)
        assertEquals("180", one(InstructionKind.HEADING, "fly heading one eight zero")?.value)
    }

    @Test
    fun `an impossible heading is rejected rather than shown`() {
        assertNull(one(InstructionKind.HEADING, "heading four five zero"))
    }

    // ── Speed ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `speed comes from either phrasing`() {
        assertEquals("180 KT", one(InstructionKind.SPEED, "reduce speed to one eight zero")?.value)
        assertEquals("230 KT", one(InstructionKind.SPEED, "maintain two three zero knots")?.value)
    }

    // ── Handoff ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a handoff carries the facility and the frequency`() {
        assertEquals("TOWER 118.1", one(InstructionKind.HANDOFF, "contact tower one one eight point one")?.value)
        assertEquals(
            "CHARLOTTE APPROACH 124.0",
            one(InstructionKind.HANDOFF, "contact charlotte approach one two four point zero")?.value,
        )
    }

    @Test
    fun `shorthand without a full frequency names the facility only`() {
        // "ground point niner" is 121.9 to a pilot, who knows ground's base frequency. The app
        // does not, so it must not print a number it worked out from nothing.
        val i = one(InstructionKind.HANDOFF, "contact ground point niner")
        assertEquals("GROUND", i?.value)
    }

    // ── Clearances ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `landing and takeoff clearances carry the runway`() {
        assertEquals("LAND 18C", one(InstructionKind.CLEARANCE, "cleared to land runway one eight center")?.value)
        assertEquals("DEPART 36L", one(InstructionKind.CLEARANCE, "cleared for takeoff runway three six left")?.value)
    }

    @Test
    fun `the clearances with no runway still register`() {
        assertEquals("GO AROUND", one(InstructionKind.CLEARANCE, "go around go around")?.value)
        assertEquals("LINE UP AND WAIT", one(InstructionKind.CLEARANCE, "line up and wait runway one eight left")?.value)
        assertEquals("ILS APPROACH", one(InstructionKind.CLEARANCE, "cleared for the ILS approach runway one eight center")?.value)
    }

    @Test
    fun `a go around outranks a landing clearance in the same breath`() {
        // Both phrases appear when a landing is cancelled. The go-around is the live instruction.
        assertEquals(
            "GO AROUND",
            one(InstructionKind.CLEARANCE, "cancel landing clearance, go around, runway one eight center")?.value,
        )
    }

    // ── Several at once ──────────────────────────────────────────────────────────────────────

    @Test
    fun `one transmission can carry several instructions`() {
        val all = instructionsFrom("Delta four fifty descend and maintain five thousand turn left heading two seven zero")
        assertEquals(2, all.size)
        assertTrue(all.any { it.kind == InstructionKind.ALTITUDE && it.value == "5000" })
        assertTrue(all.any { it.kind == InstructionKind.HEADING && it.value == "270 L" })
    }

    @Test
    fun `ordinary chatter yields nothing`() {
        assertTrue(instructionsFrom("Delta four fifty roger").isEmpty())
        assertTrue(instructionsFrom("").isEmpty())
    }

    // ── Compliance ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `an aircraft at the assigned altitude has complied`() {
        val i = Instruction(InstructionKind.ALTITUDE, "5000", 5000.0)
        assertEquals(Compliance.MET, complianceOf(i, aircraft(altitude = 5100)))
        assertEquals(Compliance.WORKING, complianceOf(i, aircraft(altitude = 7200)))
    }

    @Test
    fun `a heading counts as met once it is close, wrapping past north`() {
        // 355 to 005 is ten degrees the short way and 350 the long way. Comparing the raw
        // numbers would read a compliant aircraft as ignoring the turn.
        val i = Instruction(InstructionKind.HEADING, "005", 5.0)
        assertEquals(Compliance.MET, complianceOf(i, aircraft(track = 355.0)))
        assertEquals(Compliance.WORKING, complianceOf(i, aircraft(track = 270.0)))
        // Still outside tolerance is still working, wrap or no wrap: 340 to 005 is 25 degrees.
        assertEquals(Compliance.WORKING, complianceOf(i, aircraft(track = 340.0)))
    }

    @Test
    fun `speed has its own tolerance`() {
        val i = Instruction(InstructionKind.SPEED, "180 KT", 180.0)
        assertEquals(Compliance.MET, complianceOf(i, aircraft(speed = 190.0)))
        assertEquals(Compliance.WORKING, complianceOf(i, aircraft(speed = 260.0)))
    }

    @Test
    fun `nothing to check reads as unknown rather than as complied`() {
        val handoff = Instruction(InstructionKind.HANDOFF, "TOWER 118.1")
        assertEquals(Compliance.UNKNOWN, complianceOf(handoff, aircraft()))
        // No contact at all, and an altitude the transponder isn't reporting.
        assertEquals(Compliance.UNKNOWN, complianceOf(Instruction(InstructionKind.ALTITUDE, "5000", 5000.0), null))
        assertEquals(
            Compliance.UNKNOWN,
            complianceOf(Instruction(InstructionKind.ALTITUDE, "5000", 5000.0), aircraft(altitude = null)),
        )
    }
}
