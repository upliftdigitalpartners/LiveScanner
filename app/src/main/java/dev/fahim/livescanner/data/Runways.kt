package dev.fahim.livescanner.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * One end of one runway. A landing uses an end, not a strip: 18C and 36C are the same concrete,
 * and only the direction of travel says which one an aircraft is on.
 *
 * [headingDeg] is true, not magnetic, because that is what ADS-B reports as track — mixing the
 * two would be a ~10 degree error at Charlotte, which is most of the tolerance this match has.
 */
@Serializable
data class RunwayEnd(
    val ident: String,
    val lat: Double,
    val lon: Double,
    val headingDeg: Double,
    val lengthFt: Int = 0,
)

@Serializable
internal data class RunwayFile(val airports: Map<String, List<RunwayEnd>>)

/** Runway ends per ICAO, built from OurAirports by tools/build_runways.py. */
class Runways(private val context: Context) {

    private val byIcao: Map<String, List<RunwayEnd>> by lazy {
        try {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            AppJson.decodeFromString<RunwayFile>(text).airports
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load runways", t)
            emptyMap()
        }
    }

    fun endsFor(icao: String?): List<RunwayEnd> = byIcao[icao?.uppercase()].orEmpty()

    private companion object {
        const val ASSET = "runways.json"
        const val TAG = "Runways"
    }
}

/** Widest angle between an aircraft's track and a runway's heading still counted as lined up. */
private const val HEADING_TOLERANCE_DEG = 45.0

/** How far off the extended centreline an aircraft may be, in nautical miles. */
private const val CROSS_LIMIT_NM = 1.2

/** How far along the centreline to look, from short final through rollout. */
private const val ALONG_MIN_NM = -8.0
private const val ALONG_MAX_NM = 4.0

/**
 * The runway end an aircraft is lined up with, or null when it isn't on one.
 *
 * Heading is decisive and distance is the tie-break, in that order. Reversing it would be a bug:
 * the two ends of a strip share a centreline, so position alone cannot tell 18C from 36C, and at
 * a field like Charlotte with three parallels the wrong order picks the wrong runway more often
 * than not.
 *
 * Pure so it can be tested against real threshold coordinates without an Android context.
 */
fun matchRunway(
    ends: List<RunwayEnd>,
    lat: Double,
    lon: Double,
    trackDeg: Double,
): RunwayEnd? {
    var best: RunwayEnd? = null
    var bestCross = Double.MAX_VALUE
    var bestLength = -1

    for (end in ends) {
        if (angleBetween(trackDeg, end.headingDeg) > HEADING_TOLERANCE_DEG) continue

        // Nautical miles north and east of the threshold; longitude shrinks with latitude.
        val north = (lat - end.lat) * 60.0
        val east = (lon - end.lon) * 60.0 * cos(Math.toRadians(end.lat))

        // Rotate into the runway's frame: along the centreline, and across it.
        val heading = Math.toRadians(end.headingDeg)
        val along = north * cos(heading) + east * sin(heading)
        val cross = abs(-north * sin(heading) + east * cos(heading))

        if (along < ALONG_MIN_NM || along > ALONG_MAX_NM) continue
        if (cross > CROSS_LIMIT_NM) continue

        // Closest to the centreline wins; between two equally good, the longer runway is the
        // likelier one — a crossing GA strip shouldn't beat the main parallel.
        if (cross < bestCross - 1e-9 || (abs(cross - bestCross) <= 1e-9 && end.lengthFt > bestLength)) {
            best = end
            bestCross = cross
            bestLength = end.lengthFt
        }
    }
    return best
}

/** Smallest angle between two compass bearings, 0..180. */
internal fun angleBetween(a: Double, b: Double): Double {
    val diff = abs((a - b) % 360.0)
    return if (diff > 180.0) 360.0 - diff else diff
}
