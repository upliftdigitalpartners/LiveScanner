package dev.fahim.livescanner.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * One terminal gate, from OpenStreetMap by tools/build_gates.py.
 *
 * [ref] is the designation a controller says — "B12", "E15". Stands without one aren't imported,
 * because a gate nobody can name is no use on a panel.
 */
@Serializable
data class Gate(
    val ref: String,
    val lat: Double,
    val lon: Double,
    val terminal: String? = null,
)

@Serializable
internal data class GateFile(val airports: Map<String, List<Gate>>)

/**
 * Gate positions per ICAO.
 *
 * The asset is optional. Until tools/build_gates.py has been run there is no gates.json, and this
 * quietly holds nothing — which is the pre-existing behaviour, where a gate is only ever known
 * because it was said on the radio.
 */
class Gates(private val context: Context) {

    private val byIcao: Map<String, List<Gate>> by lazy {
        try {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            AppJson.decodeFromString<GateFile>(text).airports
        } catch (t: Throwable) {
            // Missing is the normal case, not a failure worth shouting about.
            Log.i(TAG, "No gate data bundled (${t.javaClass.simpleName}); gates will be radio-only")
            emptyMap()
        }
    }

    fun gatesFor(icao: String?): List<Gate> = byIcao[icao?.uppercase()].orEmpty()

    private companion object {
        const val ASSET = "gates.json"
        const val TAG = "Gates"
    }
}

/** How close a parked aircraft has to be to a gate node to be counted as at it. */
private const val GATE_RADIUS_M = 80.0

/**
 * How much nearer the winner must be than the runner-up.
 *
 * Gates sit in rows roughly 60 m apart, and the OSM node is on the terminal face rather than
 * under the aircraft, so two neighbours can come out nearly equidistant. When they do, the honest
 * answer is that we don't know — naming the wrong gate is worse than naming none.
 */
private const val GATE_MARGIN = 1.25

private const val METERS_PER_DEGREE_LAT = 111_320.0

/** Rough metres between two nearby points. Flat-earth is ample over a single airport. */
internal fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val north = (lat1 - lat2) * METERS_PER_DEGREE_LAT
    val east = (lon1 - lon2) * METERS_PER_DEGREE_LAT * cos(Math.toRadians(lat1))
    return sqrt(north * north + east * east)
}

/**
 * The gate an aircraft is parked at, or null when that can't be said confidently.
 *
 * Only meaningful for a stopped aircraft: something still taxiing passes within metres of gates
 * it isn't going to, so callers check that first.
 */
fun nearestGate(gates: List<Gate>, lat: Double, lon: Double): Gate? {
    var best: Gate? = null
    var bestDistance = Double.MAX_VALUE
    var runnerUp = Double.MAX_VALUE

    for (gate in gates) {
        val distance = metersBetween(lat, lon, gate.lat, gate.lon)
        if (distance < bestDistance) {
            runnerUp = bestDistance
            best = gate
            bestDistance = distance
        } else if (distance < runnerUp) {
            runnerUp = distance
        }
    }

    if (best == null || bestDistance > GATE_RADIUS_M) return null
    if (runnerUp != Double.MAX_VALUE && runnerUp < bestDistance * GATE_MARGIN) return null
    return best
}
