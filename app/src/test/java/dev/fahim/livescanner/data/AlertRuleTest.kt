package dev.fahim.livescanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule matching shipped broken once: it tested the raw transcript only, so a tail-number rule could
 * never fire on "november four two five kilo hotel". These pin both halves of the fix.
 */
class AlertRuleTest {

    private fun flightRule(term: String) = AlertRule(
        id = "test",
        type = RuleType.FLIGHT,
        name = "FLIGHT · $term",
        detail = "",
        terms = listOf(term),
    )

    @Test
    fun `a flight rule fires on the spoken form`() {
        assertTrue(flightRule("UAL328").matches("United three twenty eight, cleared to land"))
    }

    @Test
    fun `a flight rule fires on the resolved callsign even when the words do not contain it`() {
        // This is the case the text pass alone cannot catch.
        val rule = flightRule("UAL328")
        assertTrue(rule.matches("cleared to land runway two seven", resolvedCallsign = "UAL328"))
    }

    @Test
    fun `a tail rule fires on phonetics`() {
        val rule = AlertRule(
            id = "t",
            type = RuleType.TAIL,
            name = "",
            detail = "",
            terms = listOf("N425KH"),
        )
        assertTrue(rule.matches("november four two five kilo hotel, taxi via alpha"))
    }

    @Test
    fun `a disarmed rule never fires`() {
        assertFalse(flightRule("UAL328").copy(on = false).matches("United three twenty eight"))
    }

    @Test
    fun `a rule with no terms never fires`() {
        assertFalse(flightRule("UAL328").copy(terms = emptyList()).matches("United three twenty eight"))
    }

    @Test
    fun `keyword rules match plain text case-insensitively`() {
        val rule = AlertRule(
            id = "k",
            type = RuleType.KEYWORD,
            name = "",
            detail = "",
            terms = listOf("GO AROUND"),
        )
        assertTrue(rule.matches("Delta four fifty, go around, go around"))
        assertFalse(rule.matches("Delta four fifty, cleared to land"))
    }

    @Test
    fun `only aircraft rules are worth pinning on the scope`() {
        assertTrue(flightRule("UAL328").tracksAircraft)
        assertTrue(flightRule("X").copy(type = RuleType.TAIL).tracksAircraft)
        assertFalse(flightRule("X").copy(type = RuleType.KEYWORD).tracksAircraft)
        assertFalse(flightRule("X").copy(type = RuleType.FEED).tracksAircraft)
    }

    @Test
    fun `the seeded emergency rule catches what it advertises`() {
        val emergency = seedRules().first { it.id == "seed:emergency" }
        assertTrue(emergency.matches("Mayday mayday mayday, engine failure"))
        assertTrue(emergency.matches("we are declaring an emergency"))
        assertFalse(emergency.matches("cleared for takeoff runway one eight left"))
    }

    @Test
    fun `the seeded go-around rule ships disarmed`() {
        assertFalse(seedRules().first { it.id == "seed:goaround" }.on)
    }

    @Test
    fun `seeded rules have unique ids`() {
        val ids = seedRules().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
