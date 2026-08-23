package dev.fahim.livescanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The callsign layer is the difference between flight tracking working and silently never firing,
 * and none of it can be checked by reading: controllers say "United three twenty eight", so the
 * literal string "UAL328" appears nowhere in a transcript.
 */
class AviationTest {

    // ── Spoken numbers → digits ──────────────────────────────────────────────────────────────

    @Test
    fun `tens and units merge into one number`() {
        // "three twenty eight" is 3 then 28, not 3-20-8.
        assertEquals("UNITED 328", normalizeTranscript("United three twenty eight"))
    }

    @Test
    fun `oh is spoken zero`() {
        assertEquals("JETBLUE 1006", normalizeTranscript("JetBlue ten oh six"))
    }

    @Test
    fun `a bare tens word keeps both digits`() {
        assertEquals("DELTA 450", normalizeTranscript("Delta four fifty"))
    }

    @Test
    fun `aviation pronunciations are understood`() {
        // "niner" and "tree" are standard radio pronunciations, not typos.
        assertEquals("RUNWAY 39", normalizeTranscript("runway tree niner"))
    }

    @Test
    fun `teens are single numbers`() {
        assertEquals("HEADING 170", normalizeTranscript("heading seventeen zero"))
    }

    @Test
    fun `phonetic letters collapse into a tail number`() {
        assertEquals(
            "N 425 KH",
            normalizeSpelledOut("november four two five kilo hotel"),
        )
    }

    @Test
    fun `an airline that is also a phonetic letter survives the default normalisation`() {
        // Regression: "Delta" is both the letter D and a major carrier. Collapsing it turned every
        // Delta callsign into "D450" and stopped the airline matching at all.
        assertEquals("DELTA 450", normalizeTranscript("Delta four fifty"))
        assertEquals("D 450", normalizeSpelledOut("Delta four fifty"))
    }

    @Test
    fun `Delta flights still match despite the phonetic collision`() {
        assertTrue(transcriptMentionsCallsign("Delta four fifty, hold short", "DAL450"))
        assertTrue(transcriptMentionsCallsign("DL450 taxi to gate", "DAL450"))
    }

    @Test
    fun `ordinary words pass through untouched`() {
        assertEquals("CLEARED TO LAND", normalizeTranscript("cleared to land"))
    }

    // ── Typed input → one canonical callsign ─────────────────────────────────────────────────

    @Test
    fun `IATA prefixes become ICAO`() {
        assertEquals("UAL328", normalizeFlightNumber("UA328"))
        assertEquals("SWA1", normalizeFlightNumber("WN1"))
    }

    @Test
    fun `ICAO input is already canonical`() {
        assertEquals("UAL328", normalizeFlightNumber("ual 328"))
    }

    @Test
    fun `spoken airline names are accepted`() {
        assertEquals("UAL328", normalizeFlightNumber("United 328"))
        assertEquals("DAL450", normalizeFlightNumber("delta 450"))
    }

    @Test
    fun `nonsense is rejected rather than guessed`() {
        assertNull(normalizeFlightNumber("hello"))
        assertNull(normalizeFlightNumber(""))
        // An unknown two-letter prefix has no ICAO mapping, so it cannot be resolved.
        assertNull(normalizeFlightNumber("ZZ123"))
    }

    // ── Does this transmission address that aircraft? ────────────────────────────────────────

    @Test
    fun `spoken airline and number match the ICAO callsign`() {
        assertTrue(transcriptMentionsCallsign("United three twenty eight, cleared to land", "UAL328"))
    }

    @Test
    fun `the literal callsign matches`() {
        assertTrue(transcriptMentionsCallsign("UAL328 contact ground", "UAL328"))
    }

    @Test
    fun `a phonetic tail number matches`() {
        assertTrue(
            transcriptMentionsCallsign("november four two five kilo hotel, taxi via alpha", "N425KH"),
        )
    }

    @Test
    fun `a different flight on the same airline does not match`() {
        assertFalse(transcriptMentionsCallsign("United three twenty nine", "UAL328"))
    }

    @Test
    fun `an unrelated transmission does not match`() {
        assertFalse(transcriptMentionsCallsign("Delta four fifty, hold short", "UAL328"))
    }

    // ── Display helpers ──────────────────────────────────────────────────────────────────────

    @Test
    fun `airline name comes from the callsign prefix`() {
        assertEquals("United", airlineName("UAL328"))
        assertEquals("JetBlue", airlineName("JBU1006"))
        assertNull(airlineName(null))
    }

    @Test
    fun `an unknown type falls back to its raw code rather than disappearing`() {
        assertEquals("Boeing 737-800", friendlyType("B738"))
        assertEquals("ZZZZ", friendlyType("ZZZZ"))
        assertNull(friendlyType(null))
    }

    @Test
    fun `vertical trend has a dead band around level`() {
        assertEquals("climbing", verticalTrend(800))
        assertEquals("descending", verticalTrend(-800))
        assertEquals("level", verticalTrend(50))
        assertNull(verticalTrend(null))
    }
}
