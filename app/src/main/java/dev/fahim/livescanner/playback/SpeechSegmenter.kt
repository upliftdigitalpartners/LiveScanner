package dev.fahim.livescanner.playback

/** A stretch of the stream the squelch gate says carried speech, in wall-clock milliseconds. */
data class SpeechSpan(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Turns the squelch gate's open/closed signal into one span per transmission.
 *
 * Transcription used to cut the stream into fixed eight-second windows on a timer, which split
 * roughly every transmission that didn't happen to fit inside one. A clearance cut in half is two
 * partial transcripts that each miss words, and nothing downstream — callsign matching, the
 * instruction extractor, the gate and runway readers — can recover what was lost at the seam.
 * The gate already knows where the speech is; this just reads it.
 *
 * Pure, so the whole decision can be tested without an audio pipeline.
 */
class SpeechSegmenter(
    /** Audio kept before the gate opened, since a gate always clips the first syllable. */
    private val leadMs: Long = DEFAULT_LEAD_MS,
    /** Audio kept after it closed, which covers the gate's own release. */
    private val tailMs: Long = DEFAULT_TAIL_MS,
    /** Shorter than this is a squelch crackle, not a transmission. */
    private val minSpeechMs: Long = DEFAULT_MIN_SPEECH_MS,
    /**
     * A span is cut at this length even while the gate is still open.
     *
     * Two things make that necessary: a controller working a busy sector can key up for a long
     * time, and the gate reports permanently open when the user has switched it off. Without a
     * ceiling the second case would never produce a span at all, and transcription would simply
     * stop.
     */
    private val maxSpanMs: Long = DEFAULT_MAX_SPAN_MS,
) {
    private var openedAtMs: Long? = null

    /**
     * Feeds one gate reading in. Returns a span when one has just finished, otherwise null.
     *
     * Timestamps are whatever clock the caller uses; it only has to be the same one throughout.
     */
    fun onGate(nowMs: Long, open: Boolean): SpeechSpan? {
        val openedAt = openedAtMs

        if (open) {
            if (openedAt == null) {
                openedAtMs = nowMs
                return null
            }
            if (nowMs - openedAt < maxSpanMs) return null
            // Still talking, but long enough. Cut here and start the next span at the same
            // instant, so a long transmission is split rather than dropped.
            openedAtMs = nowMs
            return SpeechSpan(openedAt - leadMs, nowMs)
        }

        if (openedAt == null) return null
        openedAtMs = null
        if (nowMs - openedAt < minSpeechMs) return null
        return SpeechSpan(openedAt - leadMs, nowMs + tailMs)
    }

    /** Closes any span still open — for when playback stops mid-transmission. */
    fun flush(nowMs: Long): SpeechSpan? {
        val openedAt = openedAtMs ?: return null
        openedAtMs = null
        if (nowMs - openedAt < minSpeechMs) return null
        return SpeechSpan(openedAt - leadMs, nowMs + tailMs)
    }

    /** True while the gate is open and a span is being accumulated. */
    val inSpeech: Boolean get() = openedAtMs != null

    companion object {
        const val DEFAULT_LEAD_MS = 400L
        const val DEFAULT_TAIL_MS = 600L
        const val DEFAULT_MIN_SPEECH_MS = 700L
        const val DEFAULT_MAX_SPAN_MS = 15_000L
    }
}
