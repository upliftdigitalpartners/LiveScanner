package dev.fahim.livescanner.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Shared JSON codec: tolerant of unknown keys so the catalog can grow without breaking old builds. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

@Serializable
enum class FeedSource { LIVEATC, BROADCASTIFY, CUSTOM }

@Serializable
enum class FeedType { ATC, SCANNER, OTHER }

/**
 * A single listenable feed. [streamUrl] may be null for Broadcastify feeds that are
 * resolved at play time from [broadcastifyFeedId] (see StreamResolver).
 */
@Serializable
data class Feed(
    val id: String,
    val name: String,
    val source: FeedSource,
    val type: FeedType,
    val streamUrl: String? = null,
    val broadcastifyFeedId: String? = null,
    val location: String? = null,
    val region: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val description: String? = null,
    /** 4-char panel chip, e.g. KBOS. Derived from [id] when the catalog doesn't carry one. */
    val code: String? = null,
    /** Tuned frequency as displayed, e.g. "128.80". Null when unknown — the panel hides it. */
    val frequency: String? = null,
) {
    val hasCoordinates: Boolean get() = lat != null && lon != null

    /**
     * The chip shown on every panel row. LiveATC ids carry the ICAO ("liveatc:kbos_twr" → KBOS);
     * Broadcastify and custom feeds fall back to a source tag.
     */
    val displayCode: String
        get() = code?.uppercase() ?: when (source) {
            FeedSource.LIVEATC -> id.substringAfter(':').substringBefore('_').take(4).uppercase()
            FeedSource.BROADCASTIFY -> "BCFY"
            FeedSource.CUSTOM -> "CUST"
        }

    /** Secondary line for list rows: location plus a short source/type tag. */
    val subtitle: String
        get() = listOfNotNull(location, sourceTag).joinToString(" · ")

    private val sourceTag: String
        get() = when (source) {
            FeedSource.LIVEATC -> "LiveATC"
            FeedSource.BROADCASTIFY -> "Broadcastify"
            FeedSource.CUSTOM -> "Custom"
        }
}

@Serializable
data class CatalogFile(
    val version: Int = 1,
    val feeds: List<Feed> = emptyList(),
)

/** Simple coordinate pair used for "Nearby" sorting. */
data class LatLng(val lat: Double, val lon: Double)

/** Great-circle distance in nautical miles — the unit the panel reads in. */
fun Feed.distanceNmFrom(from: LatLng?): Double? = distanceKmFrom(from)?.let { it / 1.852 }

/** Great-circle distance in kilometres between this feed and [from], or null if either lacks coords. */
fun Feed.distanceKmFrom(from: LatLng?): Double? {
    if (from == null || lat == null || lon == null) return null
    val r = 6371.0
    val dLat = Math.toRadians(lat - from.lat)
    val dLon = Math.toRadians(lon - from.lon)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(from.lat)) * cos(Math.toRadians(lat)) *
        sin(dLon / 2) * sin(dLon / 2)
    return r * (2 * atan2(sqrt(a), sqrt(1 - a)))
}
