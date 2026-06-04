package dev.fahim.livescanner.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches live aircraft near a point from the free, key-less adsb.lol community API.
 * Parsed by hand because fields are sparsely populated and `alt_baro` can be a number or "ground".
 */
object AdsbClient {

    private const val TAG = "AdsbClient"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchNear(lat: Double, lon: Double, distNm: Int): List<Aircraft> =
        withContext(Dispatchers.IO) {
            try {
                val body = httpGet("https://api.adsb.lol/v2/lat/$lat/lon/$lon/dist/$distNm")
                val stamp = System.nanoTime()
                val array = json.parseToJsonElement(body).jsonObject["ac"]?.jsonArray
                    ?: return@withContext emptyList()
                array.mapNotNull { runCatching { parse(it.jsonObject, stamp) }.getOrNull() }
            } catch (t: Throwable) {
                Log.w(TAG, "ADS-B fetch failed", t)
                emptyList()
            }
        }

    private fun parse(o: JsonObject, stamp: Long): Aircraft? {
        val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
        val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return null
        val hex = o["hex"]?.jsonPrimitive?.contentOrNull ?: return null
        val altPrimitive = o["alt_baro"]?.jsonPrimitive
        val onGround = altPrimitive?.contentOrNull == "ground"
        val emergency = o["emergency"]?.jsonPrimitive?.contentOrNull
        return Aircraft(
            hex = hex,
            callsign = o["flight"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            registration = o["r"]?.jsonPrimitive?.contentOrNull,
            type = o["t"]?.jsonPrimitive?.contentOrNull,
            lat = lat,
            lon = lon,
            altitudeFt = if (onGround) null else altPrimitive?.intOrNull,
            onGround = onGround,
            groundSpeedKt = o["gs"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            trackDeg = o["track"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            squawk = o["squawk"]?.jsonPrimitive?.contentOrNull,
            emergency = emergency != null && emergency != "none",
            verticalRateFpm = o["baro_rate"]?.jsonPrimitive?.intOrNull ?: o["geom_rate"]?.jsonPrimitive?.intOrNull,
            category = o["category"]?.jsonPrimitive?.contentOrNull,
            seenNanos = stamp,
        )
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", "LiveScanner/0.1 (Android)")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
