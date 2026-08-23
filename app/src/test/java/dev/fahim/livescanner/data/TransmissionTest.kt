package dev.fahim.livescanner.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Priority drives the colour of the recorder's left rule and whether an alert reads as an
 * emergency, so the keyword tiers need to stay separated.
 */
class TransmissionTest {

    @Test
    fun `emergency phrases outrank everything`() {
        assertEquals(Priority.EMERGENCY, priorityFor("Mayday mayday, engine out"))
        assertEquals(Priority.EMERGENCY, priorityFor("we are declaring an emergency"))
        assertEquals(Priority.EMERGENCY, priorityFor("squawk 7700"))
    }

    @Test
    fun `notable phrases are flagged without being alarms`() {
        assertEquals(Priority.NOTABLE, priorityFor("Delta four fifty, go around"))
        assertEquals(Priority.NOTABLE, priorityFor("Medflight one inbound"))
    }

    @Test
    fun `ordinary clearances are routine`() {
        assertEquals(Priority.ROUTINE, priorityFor("United 328 cleared to land runway one eight left"))
        assertEquals(Priority.ROUTINE, priorityFor("contact ground point niner"))
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(Priority.EMERGENCY, priorityFor("mayday"))
        assertEquals(Priority.EMERGENCY, priorityFor("MAYDAY"))
    }

    private fun transmission(durationMs: Long) = Transmission(
        id = "t",
        timestampMs = 0L,
        feedId = "f",
        feedLabel = "KCLT TWR",
        durationMs = durationMs,
        raw = "test",
    )

    @Test
    fun `duration reads as minutes and seconds`() {
        assertEquals("0:00", transmission(0L).durationLabel)
        assertEquals("0:07", transmission(7_400L).durationLabel)
        assertEquals("1:05", transmission(65_000L).durationLabel)
    }

    @Test
    fun `a transmission carries no words until the detailed transcript supplies them`() {
        assertEquals(emptyList<TranscriptWord>(), transmission(1_000L).words)
    }
}
