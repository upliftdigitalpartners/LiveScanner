package dev.fahim.livescanner.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live ATC transcription via Groq's Whisper endpoint (OpenAI-compatible).
 *
 * [captureSegment] reads a few seconds of the raw audio stream over a fresh connection, and
 * [transcribe] uploads that clip to whisper-large-v3-turbo. Cheap (~$0.04/hr) and fast enough
 * for near-live captions. Experimental: ATC audio is noisy, so accuracy varies.
 */
object GroqTranscriber {

    private const val TAG = "GroqTranscriber"
    private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val CHAT_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "whisper-large-v3-turbo"
    private const val BOUNDARY = "----LiveScannerBoundary7s9d3"
    private const val ATC_PROMPT =
        "Air traffic control radio. Aviation phraseology: callsigns, runways, headings, " +
            "altitudes, cleared for takeoff, cleared to land, contact, traffic, squawk."

    /** Reads up to [millis] of raw audio bytes from [streamUrl] via a separate connection. */
    suspend fun captureSegment(streamUrl: String, millis: Long): ByteArray? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("User-Agent", "LiveScanner/0.1 (Android)")
                // No Icy-MetaData header → the stream is pure audio, no interleaved metadata.
            }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + millis
            conn.inputStream.use { input ->
                while (System.currentTimeMillis() < deadline) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > 4_000_000) break
                }
            }
            out.toByteArray()
        } catch (t: Throwable) {
            Log.w(TAG, "audio capture failed", t)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Uploads an audio clip to Groq Whisper; returns the transcript text (or null on failure). */
    suspend fun transcribe(audio: ByteArray, apiKey: String): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
            }
            DataOutputStream(conn.outputStream).use { body ->
                body.writeBytes("--$BOUNDARY\r\n")
                body.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"clip.mp3\"\r\n")
                body.writeBytes("Content-Type: audio/mpeg\r\n\r\n")
                body.write(audio)
                body.writeBytes("\r\n")
                textPart(body, "model", MODEL)
                textPart(body, "response_format", "text")
                textPart(body, "language", "en")
                textPart(body, "prompt", ATC_PROMPT)
                body.writeBytes("--$BOUNDARY--\r\n")
                body.flush()
            }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText().trim() }
            } else {
                Log.w(TAG, "Groq HTTP $code: ${conn.errorStream?.bufferedReader()?.use { it.readText() }}")
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "transcribe failed", t)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun textPart(body: DataOutputStream, name: String, value: String) {
        body.writeBytes("--$BOUNDARY\r\n")
        body.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        body.writeBytes(value)
        body.writeBytes("\r\n")
    }

    /** Asks a fast Groq LLM which of the on-scope callsigns the transmission addresses. */
    suspend fun identifyCallsigns(transcript: String, candidates: List<String>, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            if (candidates.isEmpty()) return@withContext emptyList()
            try {
                val system = "You parse air traffic control radio. Given a transmission and a list of " +
                    "aircraft callsigns currently in range (ICAO format like UAL328), identify which " +
                    "callsign(s) are being addressed or speaking. Airlines are spoken by name and number: " +
                    "'United three twenty eight'=UAL328, 'JetBlue ten oh six'=JBU1006, 'Delta four fifty'=DAL450. " +
                    "Reply ONLY with matching callsigns from the list, comma-separated. If none match, reply NONE."
                val user = "Transmission: \"$transcript\"\nCallsigns in range: ${candidates.joinToString(", ")}"
                val req = ChatRequest(
                    model = "llama-3.1-8b-instant",
                    messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
                )
                val response = postJson(CHAT_ENDPOINT, AppJson.encodeToString(req), apiKey)
                    ?: return@withContext emptyList()
                val content = AppJson.decodeFromString<ChatResponse>(response)
                    .choices.firstOrNull()?.message?.content?.trim().orEmpty()
                if (content.isEmpty() || content.equals("NONE", ignoreCase = true)) return@withContext emptyList()
                val wanted = content.split(",", " ", "\n").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                candidates.map { it.trim() }.filter { it.uppercase() in wanted }
            } catch (t: Throwable) {
                Log.w(TAG, "callsign match failed", t)
                emptyList()
            }
        }

    /** Rewrites an ATC transmission into plain English a non-pilot can follow. */
    suspend fun plainEnglish(transcript: String, apiKey: String): String? = withContext(Dispatchers.IO) {
        try {
            val system = "Rewrite this air traffic control transmission in plain, everyday English for a " +
                "non-pilot. One short sentence. Expand jargon: 'cleared ILS 22L'='cleared to land on runway 22 Left', " +
                "'contact ground point niner'='switch to ground control on 121.9', 'squawk 4517'='set transponder code 4517'. " +
                "Keep callsigns like United 328. If unintelligible, reply with the original text."
            val req = ChatRequest(
                model = "llama-3.1-8b-instant",
                messages = listOf(ChatMessage("system", system), ChatMessage("user", transcript)),
                temperature = 0.2,
                maxTokens = 90,
            )
            val response = postJson(CHAT_ENDPOINT, AppJson.encodeToString(req), apiKey) ?: return@withContext null
            AppJson.decodeFromString<ChatResponse>(response).choices.firstOrNull()?.message?.content?.trim()
        } catch (t: Throwable) {
            Log.w(TAG, "plainEnglish failed", t)
            null
        }
    }

    private fun postJson(url: String, json: String, apiKey: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w(TAG, "Groq chat HTTP ${conn.responseCode}: ${conn.errorStream?.bufferedReader()?.use { r -> r.readText() }}")
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "postJson failed", t)
            null
        } finally {
            conn?.disconnect()
        }
    }
}

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.0,
    @SerialName("max_tokens") val maxTokens: Int = 60,
)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

@Serializable
private data class ChatChoice(val message: ChatMessage? = null)
