package dev.fahim.livescanner.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live ATC transcription via Groq's Whisper endpoint (OpenAI-compatible).
 *
 * Audio comes from the playback service's rolling buffer, which is tee'd off the stream ExoPlayer
 * is already reading — this object never opens its own connection to a feed. Cheap (~$0.04/hr) and
 * fast enough for near-live captions. Experimental: ATC audio is noisy, so accuracy varies.
 */
object GroqTranscriber {

    private const val TAG = "GroqTranscriber"
    private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val CHAT_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "whisper-large-v3-turbo"
    private const val BOUNDARY = "----LiveScannerBoundary7s9d3"

    /** Uploads an audio clip to Groq Whisper; returns the transcript text (or null on failure). */
    suspend fun transcribe(audio: ByteArray, apiKey: String): String? = withContext(Dispatchers.IO) {
        postAudio(audio, apiKey, responseFormat = "text", prompt = airportPrompt(null))?.trim()
    }

    /**
     * Same upload as [transcribe] but asks for word-level timings, so the caller can highlight
     * words during playback. [airportIcao] primes the decoder with that field's local vocabulary.
     */
    suspend fun transcribeDetailed(
        audio: ByteArray,
        apiKey: String,
        airportIcao: String? = null,
    ): Transcript? = withContext(Dispatchers.IO) {
        val body = postAudio(
            audio = audio,
            apiKey = apiKey,
            responseFormat = "verbose_json",
            prompt = airportPrompt(airportIcao),
            wordTimestamps = true,
        ) ?: return@withContext null
        try {
            val parsed = AppJson.decodeFromString<VerboseTranscription>(body)
            Transcript(
                text = parsed.text.trim(),
                words = parsed.words.map {
                    Word(
                        text = it.word.trim(),
                        startMs = (it.start * 1000.0).toLong(),
                        endMs = (it.end * 1000.0).toLong(),
                    )
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "verbose_json parse failed", t)
            null
        }
    }

    /** Posts the clip as multipart/form-data and returns the raw response body. */
    private fun postAudio(
        audio: ByteArray,
        apiKey: String,
        responseFormat: String,
        prompt: String,
        wordTimestamps: Boolean = false,
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
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
                textPart(body, "response_format", responseFormat)
                textPart(body, "language", "en")
                textPart(body, "prompt", prompt)
                if (wordTimestamps) textPart(body, "timestamp_granularities[]", "word")
                body.writeBytes("--$BOUNDARY--\r\n")
                body.flush()
            }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
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

    /** Rates how far a transmission departs from routine phraseology, 0..1, and says why. */
    suspend fun anomalyScore(transcript: String, apiKey: String): Anomaly? = withContext(Dispatchers.IO) {
        try {
            val system = "You rate air traffic control transmissions for how unusual they are. " +
                "Score 0.0 for entirely routine traffic: clearances, handoffs, readbacks, taxi " +
                "instructions, altitude and heading changes. Score toward 1.0 for the unusual: " +
                "emergencies, equipment problems, unusual requests, confusion or repeated " +
                "readbacks, go-arounds, medical situations. " +
                "Reply with strict JSON only: {\"score\":0.0,\"reason\":\"...\"}. " +
                "Keep reason under 12 words. No text outside the JSON."
            val req = ChatRequest(
                model = "llama-3.1-8b-instant",
                messages = listOf(ChatMessage("system", system), ChatMessage("user", transcript)),
                temperature = 0.0,
                maxTokens = 80,
            )
            val response = postJson(CHAT_ENDPOINT, AppJson.encodeToString(req), apiKey) ?: return@withContext null
            val content = AppJson.decodeFromString<ChatResponse>(response)
                .choices.firstOrNull()?.message?.content.orEmpty()
            val json = jsonObjectIn(content) ?: return@withContext null
            val wire = AppJson.decodeFromString<AnomalyWire>(json)
            val reason = wire.reason.trim()
            if (reason.isEmpty()) return@withContext null
            Anomaly(score = wire.score.coerceIn(0.0, 1.0).toFloat(), reason = reason)
        } catch (t: Throwable) {
            Log.w(TAG, "anomalyScore failed", t)
            null
        }
    }

    /** Answers a free-text question about what has been heard recently. */
    suspend fun askAboutTranscript(question: String, transcript: List<String>, apiKey: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val lines = transcript.takeLast(60)
                if (lines.isEmpty()) return@withContext null
                val log = lines.mapIndexed { i, line -> "${i + 1}. ${line.trim()}" }.joinToString("\n")
                val system = "You answer questions about a log of air traffic control radio " +
                    "transmissions. Use only what the log says — never guess or fill in aviation " +
                    "knowledge that isn't there. If the log does not contain the answer, say so " +
                    "plainly. Two sentences maximum, plain English for someone who is not a pilot."
                val user = "Log:\n$log\n\nQuestion: $question"
                val req = ChatRequest(
                    model = "llama-3.1-8b-instant",
                    messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
                    temperature = 0.2,
                    maxTokens = 160,
                )
                val response = postJson(CHAT_ENDPOINT, AppJson.encodeToString(req), apiKey) ?: return@withContext null
                AppJson.decodeFromString<ChatResponse>(response)
                    .choices.firstOrNull()?.message?.content?.trim()?.takeIf { it.isNotEmpty() }
            } catch (t: Throwable) {
                Log.w(TAG, "askAboutTranscript failed", t)
                null
            }
        }

    /** Smallest slice of [raw] that could be a JSON object — models like to add a sentence around it. */
    private fun jsonObjectIn(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else null
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

/** One word as Whisper timed it, millis from the start of the clip. */
data class Word(val text: String, val startMs: Long, val endMs: Long)

/** A transcript plus its word timings; [words] is empty when the API returned none. */
data class Transcript(val text: String, val words: List<Word>)

/** How unusual a transmission is (0..1) and a short human reason. */
data class Anomaly(val score: Float, val reason: String)

/** Whisper's verbose_json shape: start/end are seconds as doubles. */
@Serializable
private data class VerboseTranscription(
    val text: String = "",
    val words: List<VerboseWord> = emptyList(),
)

@Serializable
private data class VerboseWord(
    val word: String = "",
    val start: Double = 0.0,
    val end: Double = 0.0,
)

@Serializable
private data class AnomalyWire(val score: Double = 0.0, val reason: String = "")

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
