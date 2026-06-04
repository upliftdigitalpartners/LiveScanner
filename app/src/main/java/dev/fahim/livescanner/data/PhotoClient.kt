package dev.fahim.livescanner.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** A photo of a specific aircraft from the free planespotters.net API (looked up by ICAO hex). */
object PhotoClient {

    private const val TAG = "PhotoClient"
    private val json = Json { ignoreUnknownKeys = true }

    data class Photo(val url: String, val credit: String?, val link: String?)

    suspend fun fetch(hex: String): Photo? = withContext(Dispatchers.IO) {
        try {
            val body = httpGet("https://api.planespotters.net/pub/photos/hex/${hex.lowercase()}")
            val first = json.parseToJsonElement(body).jsonObject["photos"]?.jsonArray
                ?.firstOrNull()?.jsonObject ?: return@withContext null
            val url = first["thumbnail_large"]?.jsonObject?.get("src")?.jsonPrimitive?.contentOrNull
                ?: first["thumbnail"]?.jsonObject?.get("src")?.jsonPrimitive?.contentOrNull
                ?: return@withContext null
            Photo(
                url = url,
                credit = first["photographer"]?.jsonPrimitive?.contentOrNull,
                link = first["link"]?.jsonPrimitive?.contentOrNull,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "photo fetch failed", t)
            null
        }
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
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
