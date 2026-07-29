package dev.fahim.livescanner.data

import kotlinx.serialization.Serializable

@Serializable
enum class RuleType { KEYWORD, TAIL, FEED }

/** Accent hue for a rule's glyph, resolved against the palette at draw time. */
@Serializable
enum class RuleAccent { RED, CYAN, MAGENTA, GREEN, AMBER }

/**
 * One armed watch condition. [pattern] is matched case-insensitively against each transcript as it
 * comes off the transcriber; [terms] lets a single rule cover several phrasings.
 */
@Serializable
data class AlertRule(
    val id: String,
    val type: RuleType,
    val name: String,
    val detail: String,
    val on: Boolean = true,
    val accent: RuleAccent = RuleAccent.CYAN,
    val terms: List<String> = emptyList(),
) {
    fun matches(transcript: String): Boolean {
        if (!on || terms.isEmpty()) return false
        val upper = transcript.uppercase()
        return terms.any { it.isNotBlank() && it.uppercase() in upper }
    }
}

/** The rules a fresh install starts with, straight from the design spec. */
fun seedRules(): List<AlertRule> = listOf(
    AlertRule(
        id = "seed:emergency",
        type = RuleType.KEYWORD,
        name = "EMERGENCY PHRASES",
        detail = "MAYDAY · PAN-PAN · DECLARING · SQUAWK 7700",
        accent = RuleAccent.RED,
        terms = listOf("MAYDAY", "PAN-PAN", "PAN PAN", "DECLARING", "SQUAWK 7700"),
    ),
    AlertRule(
        id = "seed:tail",
        type = RuleType.TAIL,
        name = "TAIL NUMBER N425KH",
        detail = "Notify whenever my aircraft is addressed",
        accent = RuleAccent.CYAN,
        terms = listOf("N425KH"),
    ),
    AlertRule(
        id = "seed:goaround",
        type = RuleType.KEYWORD,
        name = "KEYWORD · GO AROUND",
        detail = "All ATC feeds · sound + banner",
        on = false,
        accent = RuleAccent.MAGENTA,
        terms = listOf("GO AROUND"),
    ),
    AlertRule(
        id = "seed:medflight",
        type = RuleType.FEED,
        name = "MEDFLIGHT / LIFEGUARD",
        detail = "Air ambulance traffic on any feed",
        accent = RuleAccent.GREEN,
        terms = listOf("MEDFLIGHT", "LIFEGUARD"),
    ),
)

/** A fired alert, shown as the red banner (phone) or heads-up card (car). */
data class ActiveAlert(
    val title: String,
    val body: String,
    val ruleId: String? = null,
)
