package dev.fahim.livescanner.data

import kotlin.math.abs

/**
 * What a controller just told an aircraft to do.
 *
 * The transcript already gets mined for a runway and a gate; this pulls out the rest of the
 * instruction — the altitude, heading, speed, handoff or clearance. For a followed flight that
 * turns the chatter from a wall of text into a short answer to "what is it doing?".
 *
 * The pay-off is [complianceOf]: the app has the instruction from the radio *and* the track from
 * the transponder, so it can say whether the aircraft has actually done it yet. Neither source
 * can show that alone.
 *
 * Patterns here were written against real normaliser output, not guessed — "descend and maintain
 * five thousand" arrives as "DESCEND AND MAINTAIN 5 THOUSAND", which is why the altitude rules
 * below deal in thousands and hundreds rather than plain digits.
 */

enum class InstructionKind(val label: String) {
    ALTITUDE("ALT"),
    HEADING("HDG"),
    SPEED("SPD"),
    HANDOFF("FREQ"),
    CLEARANCE("CLR"),
}

/**
 * One instruction. [target] is the numeric form where there is one, so compliance can be checked;
 * a handoff or a landing clearance has nothing to compare a track against, so it stays null.
 */
data class Instruction(
    val kind: InstructionKind,
    val value: String,
    val target: Double? = null,
)

/** Whether the aircraft has done what it was told, as far as the transponder shows. */
enum class Compliance { MET, WORKING, UNKNOWN }

// Altitudes are spoken in thousands and hundreds, or as a flight level.
private val FLIGHT_LEVEL = Regex("""FLIGHT LEVEL (\d{2,3})""")
private val THOUSANDS = Regex("""(\d{1,3}) THOUSAND(?: (\d) HUNDRED)?""")
private val ALTITUDE_VERB = Regex("""\b(DESCEND|CLIMB|MAINTAIN)\b""")

private val HEADING = Regex("""(?:TURN (LEFT|RIGHT) )?(?:FLY )?HEADING (\d{1,3})""")
private val SPEED = Regex("""SPEED (?:TO )?(\d{2,3})|(\d{2,3}) KNOTS""")

private val CONTACT = Regex("""CONTACT ((?:[A-Z]+ ){0,2}(?:TOWER|GROUND|APPROACH|DEPARTURE|CENTER|CENTRE|CLEARANCE|RAMP|RADAR))""")
private val FREQUENCY = Regex("""\b(\d{3}) (?:POINT|DECIMAL) (\d{1,2})\b""")

private val LAND = Regex("""CLEARED TO LAND(?: RUNWAY (\d{1,2})\s*(LEFT|RIGHT|CENTER|CENTRE)?)?""")
private val TAKEOFF = Regex("""CLEARED FOR TAKEOFF(?: RUNWAY (\d{1,2})\s*(LEFT|RIGHT|CENTER|CENTRE)?)?""")
private val APPROACH = Regex("""CLEARED (?:FOR THE )?(ILS|VISUAL|RNAV|LOCALIZER)""")
private val LINE_UP = Regex("""LINE UP AND WAIT""")
private val GO_AROUND = Regex("""GO AROUND""")

private val SIDE_LETTER = mapOf("LEFT" to "L", "RIGHT" to "R", "CENTER" to "C", "CENTRE" to "C")

/** Tolerances for calling an instruction done. Loose enough that noise isn't read as disobedience. */
private const val ALTITUDE_TOLERANCE_FT = 250.0
private const val HEADING_TOLERANCE_DEG = 12.0
private const val SPEED_TOLERANCE_KT = 20.0

/**
 * Every instruction a transmission contains, in no particular order.
 *
 * One transmission routinely carries several — "Delta four fifty, descend and maintain five
 * thousand, turn left heading two seven zero" is two.
 */
fun instructionsFrom(transcript: String): List<Instruction> {
    val text = normalizeTranscript(transcript.replace('-', ' '))
    return listOfNotNull(
        altitudeIn(text),
        headingIn(text),
        speedIn(text),
        handoffIn(text),
        clearanceIn(text),
    )
}

private fun altitudeIn(text: String): Instruction? {
    // A verb is required: "traffic at five thousand" is information about someone else, not an
    // instruction to this aircraft, and reading it as one would be worse than missing it.
    if (!ALTITUDE_VERB.containsMatchIn(text)) return null

    FLIGHT_LEVEL.find(text)?.let {
        val level = it.groupValues[1].toInt()
        return Instruction(InstructionKind.ALTITUDE, "FL$level", level * 100.0)
    }
    THOUSANDS.find(text)?.let {
        val thousands = it.groupValues[1].toInt()
        val hundreds = it.groupValues[2].toIntOrNull() ?: 0
        val feet = thousands * 1000 + hundreds * 100
        return Instruction(InstructionKind.ALTITUDE, feet.toString(), feet.toDouble())
    }
    return null
}

private fun headingIn(text: String): Instruction? {
    val match = HEADING.find(text) ?: return null
    val turn = match.groupValues[1]
    val degrees = match.groupValues[2].toIntOrNull() ?: return null
    if (degrees > 360) return null
    val shown = degrees.toString().padStart(3, '0')
    return Instruction(
        InstructionKind.HEADING,
        if (turn.isEmpty()) shown else "$shown ${turn.first()}",
        degrees.toDouble(),
    )
}

private fun speedIn(text: String): Instruction? {
    val match = SPEED.find(text) ?: return null
    val knots = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toIntOrNull() ?: return null
    return Instruction(InstructionKind.SPEED, "$knots KT", knots.toDouble())
}

private fun handoffIn(text: String): Instruction? {
    val match = CONTACT.find(text) ?: return null
    val facility = match.groupValues[1].trim()
    // "contact ground point niner" is 121.9 to a pilot, but only because they know ground's base
    // frequency. The app doesn't, so it names the facility and leaves the number out rather than
    // inventing one.
    val frequency = FREQUENCY.find(text)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
    return Instruction(
        InstructionKind.HANDOFF,
        if (frequency == null) facility else "$facility $frequency",
    )
}

private fun clearanceIn(text: String): Instruction? {
    GO_AROUND.find(text)?.let { return Instruction(InstructionKind.CLEARANCE, "GO AROUND") }
    LAND.find(text)?.let {
        return Instruction(InstructionKind.CLEARANCE, "LAND ${runwayOf(it.groupValues[1], it.groupValues[2])}".trim())
    }
    TAKEOFF.find(text)?.let {
        return Instruction(InstructionKind.CLEARANCE, "DEPART ${runwayOf(it.groupValues[1], it.groupValues[2])}".trim())
    }
    LINE_UP.find(text)?.let { return Instruction(InstructionKind.CLEARANCE, "LINE UP AND WAIT") }
    APPROACH.find(text)?.let { return Instruction(InstructionKind.CLEARANCE, "${it.groupValues[1]} APPROACH") }
    return null
}

/** Runway ident from a clearance's captured number and side, zero padded like the chart. */
private fun runwayOf(number: String, side: String): String {
    if (number.isEmpty()) return ""
    val padded = number.trimStart('0').padStart(2, '0').takeLast(2)
    return padded + (SIDE_LETTER[side] ?: "")
}

/**
 * Has the aircraft done it yet?
 *
 * [Compliance.UNKNOWN] covers both "this instruction has nothing to check" (a handoff) and "the
 * transponder isn't reporting the field we'd need" — the panel shows nothing either way rather
 * than guessing.
 */
fun complianceOf(instruction: Instruction, ac: Aircraft?): Compliance {
    val target = instruction.target ?: return Compliance.UNKNOWN
    if (ac == null) return Compliance.UNKNOWN
    return when (instruction.kind) {
        InstructionKind.ALTITUDE -> {
            val altitude = ac.altitudeFt?.toDouble() ?: return Compliance.UNKNOWN
            if (abs(altitude - target) <= ALTITUDE_TOLERANCE_FT) Compliance.MET else Compliance.WORKING
        }
        InstructionKind.HEADING ->
            if (angleBetween(ac.trackDeg, target) <= HEADING_TOLERANCE_DEG) Compliance.MET else Compliance.WORKING
        InstructionKind.SPEED ->
            if (abs(ac.groundSpeedKt - target) <= SPEED_TOLERANCE_KT) Compliance.MET else Compliance.WORKING
        else -> Compliance.UNKNOWN
    }
}
