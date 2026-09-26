package dev.fahim.livescanner.data

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Facts about a flight that come out of the radio rather than off the transponder.
 *
 * ADS-B carries position, altitude, speed and track. It does not carry a gate, and it only
 * implies a runway. The controller says both out loud, minutes before either becomes visible in
 * the track — so for a single followed flight the audio the app is already transcribing is the
 * better source, and the transponder is the confirmation.
 *
 * Everything here is pure string and arithmetic work so it can be tested without a device.
 */

/** Where a fact came from, so the panel can say whether it was heard or observed. */
enum class FactSource { RADIO, ADSB }

private val SIDES = mapOf(
    "LEFT" to "L", "RIGHT" to "R", "CENTER" to "C", "CENTRE" to "C",
)

private val RUNWAY = Regex("""RUNWAY\s+(\d{1,2})\s*(LEFT|RIGHT|CENTER|CENTRE)?""")

// A gate is a letter and a number ("C15"), a bare number at fields that don't use concourse
// letters ("42"), or the letter alone spoken separately ("gate charlie, one five").
private val GATE = Regex("""(?:GATE|STAND)\s+([A-Z]{1,2})?\s*(\d{1,3})""")

/**
 * The runway a transmission names, as an ident like "18L", or null.
 *
 * The result is a *candidate*: "cleared to land runway one eight, left turn after landing"
 * normalises to "... RUNWAY 18 LEFT TURN ..." and reads as 18L. Validate it against the field's
 * real runway list — see [validRunway] — before showing it to anyone.
 */
fun runwayFromTranscript(transcript: String): String? {
    val match = RUNWAY.find(normalizeTranscript(transcript.replace('-', ' '))) ?: return null
    val (number, side) = match.destructured
    // OurAirports idents are zero padded: a controller's "runway five" is "05" on the chart.
    val padded = number.trimStart('0').padStart(2, '0').takeLast(2)
    return padded + (SIDES[side] ?: "")
}

/** True when [ident] is actually a runway at this field — the guard on [runwayFromTranscript]. */
fun validRunway(ident: String?, ends: List<RunwayEnd>): Boolean =
    ident != null && ends.any { it.ident.equals(ident, ignoreCase = true) }

/**
 * The gate a transmission names, as "C15", or null.
 *
 * Read off the phonetic-collapsed text, because a gate is spelled out: "taxi to gate charlie one
 * five" only becomes "GATE C 15" once CHARLIE has been folded back into C.
 */
fun gateFromTranscript(transcript: String): String? {
    val match = GATE.find(normalizeSpelledOut(transcript.replace('-', ' '))) ?: return null
    val (letter, number) = match.destructured
    return (letter + number.trimStart('0').ifEmpty { "0" }).ifBlank { null }
}

/** Where a flight is in its arrival or departure, as far as the transponder can tell. */
enum class FlightPhase(val label: String) {
    PARKED("AT GATE"),
    TAXI("TAXIING"),
    DEPARTURE("DEPARTING"),
    CLIMB("CLIMBING"),
    CRUISE("EN ROUTE"),
    DESCENT("DESCENDING"),
    APPROACH("ON APPROACH"),
    UNKNOWN("—"),
}

/** Inside this range of the field, a climb or descent is a departure or an arrival. */
private const val TERMINAL_AREA_NM = 20.0

/** Vertical rate that counts as deliberate rather than turbulence, in feet per minute. */
private const val VERTICAL_THRESHOLD_FPM = 300

/** Ground speed below which an aircraft is stopped rather than taxiing, in knots. */
private const val STOPPED_KT = 5.0

/**
 * The flight's phase. [distanceNm] is its distance from the field being listened to; pass null
 * when that isn't known, and near-field phases degrade to the plain climb/descent ones.
 */
fun phaseOf(ac: Aircraft, distanceNm: Double?): FlightPhase {
    if (ac.onGround) return if (ac.groundSpeedKt < STOPPED_KT) FlightPhase.PARKED else FlightPhase.TAXI

    val near = distanceNm != null && distanceNm <= TERMINAL_AREA_NM
    val rate = ac.verticalRateFpm ?: 0
    return when {
        rate > VERTICAL_THRESHOLD_FPM -> if (near) FlightPhase.DEPARTURE else FlightPhase.CLIMB
        rate < -VERTICAL_THRESHOLD_FPM -> if (near) FlightPhase.APPROACH else FlightPhase.DESCENT
        else -> FlightPhase.CRUISE
    }
}

private val POINTS = listOf(
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
)

/** Compass point for a bearing, to 16 points: 315 -> "NW". */
fun compassPoint(bearingDeg: Double): String {
    val normalized = ((bearingDeg % 360.0) + 360.0) % 360.0
    return POINTS[(normalized / 22.5).roundToInt() % POINTS.size]
}

/** Bearing and range from a point to an aircraft, for the "8 NM northwest" readout. */
data class Bearing(val bearingDeg: Double, val distanceNm: Double) {
    val point: String get() = compassPoint(bearingDeg)
}

fun bearingTo(fromLat: Double, fromLon: Double, ac: Aircraft): Bearing {
    val north = (ac.lat - fromLat) * 60.0
    val east = (ac.lon - fromLon) * 60.0 * cos(Math.toRadians(fromLat))
    val distance = sqrt(north * north + east * east)
    // atan2(east, north) puts 0 at north and grows clockwise, which is how bearings are read.
    var bearing = Math.toDegrees(kotlin.math.atan2(east, north))
    if (bearing < 0) bearing += 360.0
    return Bearing(bearing, distance)
}

/** "8 NM northwest", or "overhead" when it is close enough that a bearing means nothing. */
fun bearingPhrase(bearing: Bearing): String =
    if (bearing.distanceNm < 1.0) "overhead" else "${bearing.distanceNm.roundToInt()} NM ${bearing.point}"
