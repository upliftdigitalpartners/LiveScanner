package dev.fahim.livescanner.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Precipitation radar imagery from RainViewer — free, no key, no account.
 *
 * The service publishes an index of recent radar frames; each frame is a set of Web Mercator tiles.
 * The index is refreshed on a timer rather than per tile, because a frame covers the whole world
 * for about ten minutes and re-fetching it per tile would be wasteful.
 */
object RainViewerClient {

    private const val TAG = "RainViewer"
    private const val INDEX = "https://api.rainviewer.com/public/weather-maps.json"

    /** How long a frame index stays usable before it is re-fetched. */
    private const val INDEX_TTL_MS = 5 * 60 * 1000L

    /** Colour scheme 4 reads as classic weather-radar green/yellow/red on a dark background. */
    private const val COLOR_SCHEME = 4

    /** Smoothing on, snow shown as its own colour. */
    private const val TILE_OPTIONS = "1_1"

    private const val TILE_SIZE = 256

    data class Frame(val host: String, val path: String, val timeSec: Long)

    @Volatile
    private var cached: Frame? = null

    @Volatile
    private var cachedAtMs = 0L

    /** The most recent radar frame, from cache when it is still fresh. */
    suspend fun latestFrame(): Frame? {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAtMs < INDEX_TTL_MS) return it }

        return withContext(Dispatchers.IO) {
            try {
                val root = AppJson.parseToJsonElement(httpGetText(INDEX)).jsonObject
                val host = root["host"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
                // "past" runs oldest to newest, so the live frame is the last entry.
                val past = root["radar"]?.jsonObject?.get("past")?.jsonArray
                val newest = past?.lastOrNull()?.jsonObject ?: return@withContext null
                val path = newest["path"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
                val time = newest["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                Frame(host, path, time).also {
                    cached = it
                    cachedAtMs = now
                }
            } catch (t: Throwable) {
                Log.w(TAG, "radar index fetch failed", t)
                null
            }
        }
    }

    fun tileUrl(frame: Frame, tile: MercatorTiles.Tile): String =
        "${frame.host}${frame.path}/$TILE_SIZE/${tile.z}/${tile.x}/${tile.y}/$COLOR_SCHEME/$TILE_OPTIONS.png"

    /** Raw PNG bytes for one tile, or null when the tile is missing or the fetch fails. */
    suspend fun tile(frame: Frame, tile: MercatorTiles.Tile): ByteArray? = withContext(Dispatchers.IO) {
        try {
            httpGetBytes(tileUrl(frame, tile))
        } catch (t: Throwable) {
            null // a tile with no coverage is a normal 404, not worth logging per tile
        }
    }

    private fun httpGetText(urlStr: String): String {
        val conn = open(urlStr)
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGetBytes(urlStr: String): ByteArray? {
        val conn = open(urlStr)
        return try {
            if (conn.responseCode !in 200..299) return null
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            }
            out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun open(urlStr: String): HttpURLConnection =
        (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "LiveScanner/0.1 (Android; personal use)")
        }
}
