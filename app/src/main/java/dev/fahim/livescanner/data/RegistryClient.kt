package dev.fahim.livescanner.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/**
 * Who an aircraft actually is, from the free FAA civil registry lookup — no key, no account.
 *
 * ADS-B already hands us a Mode S hex for every contact, which is the registry's own key, so this
 * turns a bare hex into a real make, model and owner. It fills the gap [friendlyType] can't: that
 * is a short hand-written table of type codes, and most contacts fall outside it.
 *
 * The response shape is read defensively on purpose. The endpoint is documented loosely, so rather
 * than bind to one schema this tries a few plausible field names and gives up quietly — a miss
 * leaves the UI showing exactly what it showed before. On the first shape miss it logs a trimmed
 * body under [TAG] so the mapping can be pinned exactly.
 */
object RegistryClient {

    private const val TAG = "RegistryClient"
    private const val BASE = "https://n-number.starfile.org/api"

    /** Endpoints to try, in order; the first that returns usable JSON wins and is remembered. */
    private val PATHS = listOf("modes", "hex", "icao")

    @Volatile
    private var workingPath: String? = null

    @Volatile
    private var loggedShapeMiss = false

    /** Bounded cache, negatives included — a hex that isn't in the registry never will be. */
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Registration?>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Registration?>) =
                size > CACHE_LIMIT
        },
    )

    data class Registration(
        val nNumber: String?,
        val manufacturer: String?,
        val model: String?,
        val year: String?,
        val owner: String?,
    ) {
        /** "CESSNA 172S", or whichever half is known. Null when the record carried neither. */
        val typeLabel: String?
            get() = listOfNotNull(manufacturer, model)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }

        val isEmpty: Boolean
            get() = typeLabel == null && owner.isNullOrBlank() && nNumber.isNullOrBlank()
    }

    /**
     * US civil aircraft occupy the Mode S block starting at 0xA00000. Anything outside it will
     * never be in an FAA registry, so it is skipped without a request rather than 404ing.
     */
    fun isUsRegistered(hex: String): Boolean {
        val code = hex.trim().removePrefix("~").lowercase()
        return code.length == 6 && code[0] == 'a'
    }

    suspend fun lookup(hex: String): Registration? {
        val key = hex.trim().lowercase()
        if (key.isEmpty() || !isUsRegistered(key)) return null
        cache[key]?.let { return it }
        if (cache.containsKey(key)) return null // cached negative

        return withContext(Dispatchers.IO) {
            val result = fetch(key)
            cache[key] = result
            result
        }
    }

    private fun fetch(hex: String): Registration? {
        val order = workingPath?.let { listOf(it) + PATHS.filterNot { p -> p == it } } ?: PATHS
        for (path in order) {
            val body = try {
                httpGet("$BASE/$path/$hex")
            } catch (t: Throwable) {
                continue // wrong path for this service, or a transient failure; try the next
            }
            val parsed = parse(body)
            if (parsed != null && !parsed.isEmpty) {
                workingPath = path
                return parsed
            }
            if (!loggedShapeMiss && body.isNotBlank()) {
                loggedShapeMiss = true
                Log.w(TAG, "registry returned an unrecognised shape: ${body.take(400)}")
            }
        }
        return null
    }

    private fun parse(body: String): Registration? = try {
        val root = AppJson.parseToJsonElement(body).jsonObject
        // Some services wrap the record; unwrap one level before reading fields.
        val record = listOf("data", "result", "aircraft", "record")
            .firstNotNullOfOrNull { root[it] as? JsonObject }
            ?: root
        Registration(
            nNumber = record.firstString("n_number", "nNumber", "n-number", "registration", "reg"),
            manufacturer = record.firstString("manufacturer", "manufacturer_name", "mfr", "make"),
            model = record.firstString("model", "model_name", "mfr_model", "type"),
            year = record.firstString("year", "year_mfr", "year_manufactured", "manufactured"),
            owner = record.firstString("owner", "registered_owner", "registrant", "name"),
        )
    } catch (t: Throwable) {
        null
    }

    private fun JsonObject.firstString(vararg names: String): String? {
        for (name in names) {
            val value = this[name]?.jsonPrimitive?.contentOrNull?.trim()
            if (!value.isNullOrBlank() && !value.equals("null", ignoreCase = true)) return value
        }
        return null
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "LiveScanner/0.1 (Android; personal use)")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private const val CACHE_LIMIT = 256
}
