package dev.fahim.livescanner.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmenterTest {

    private fun segmenter() = SpeechSegmenter(
        leadMs = 400,
        tailMs = 600,
        minSpeechMs = 700,
        maxSpanMs = 15_000,
    )

    /** Feeds a gate that opens at [openAt] and closes at [closeAt]; returns the span, if any. */
    private fun transmission(openAt: Long, closeAt: Long): SpeechSpan? {
        val s = segmenter()
        assertNull(s.onGate(openAt, true))
        return s.onGate(closeAt, false)
    }

    @Test
    fun `a transmission becomes one span, padded at both ends`() {
        val span = transmission(10_000, 14_000)!!
        // Lead comes off the front because a gate always clips the first syllable, and the tail
        // covers its own release.
        assertEquals(9_600, span.startMs)
        assertEquals(14_600, span.endMs)
        assertEquals(5_000, span.durationMs)
    }

    @Test
    fun `nothing is emitted while the gate is still open`() {
        val s = segmenter()
        assertNull(s.onGate(1_000, true))
        assertNull(s.onGate(2_000, true))
        assertNull(s.onGate(3_000, true))
        assertTrue(s.inSpeech)
    }

    @Test
    fun `a squelch crackle is not a transmission`() {
        // The gate flickering open for a moment is noise breaking through, not someone talking.
        assertNull(transmission(10_000, 10_300))
        assertNull(transmission(10_000, 10_699))
    }

    @Test
    fun `just past the minimum does count`() {
        assertNotNull(transmission(10_000, 10_700))
    }

    @Test
    fun `silence alone produces nothing`() {
        val s = segmenter()
        assertNull(s.onGate(1_000, false))
        assertNull(s.onGate(2_000, false))
        assertFalse(s.inSpeech)
    }

    @Test
    fun `back to back transmissions come out separately`() {
        val s = segmenter()
        s.onGate(1_000, true)
        val first = s.onGate(4_000, false)!!
        s.onGate(5_000, true)
        val second = s.onGate(9_000, false)!!
        assertEquals(600, first.startMs)
        assertEquals(4_600, first.endMs)
        assertEquals(4_600, second.startMs)
        assertEquals(9_600, second.endMs)
    }

    // ── The ceiling ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a long transmission is cut rather than held`() {
        val s = segmenter()
        assertNull(s.onGate(0, true))
        assertNull(s.onGate(14_999, true))
        val cut = s.onGate(15_000, true)!!
        assertEquals(-400, cut.startMs)
        assertEquals(15_000, cut.endMs)
        // The next span picks up exactly where that one stopped, so nothing is dropped.
        assertTrue(s.inSpeech)
        val rest = s.onGate(18_000, false)!!
        assertEquals(14_600, rest.startMs)
    }

    @Test
    fun `a gate that never closes still produces spans`() {
        // Switching the noise gate off reports permanently open. Without the ceiling this case
        // would never emit anything and transcription would simply stop.
        val s = segmenter()
        s.onGate(0, true)
        val spans = (1..3).mapNotNull { s.onGate(it * 15_000L, true) }
        assertEquals(3, spans.size)
        assertTrue(spans.all { it.durationMs in 14_000..16_000 })
    }

    // ── Flush ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `flush closes a transmission cut off by playback stopping`() {
        val s = segmenter()
        s.onGate(1_000, true)
        val span = s.flush(5_000)!!
        assertEquals(600, span.startMs)
        assertEquals(5_600, span.endMs)
        assertFalse(s.inSpeech)
    }

    @Test
    fun `flush with nothing open, or on a crackle, yields nothing`() {
        assertNull(segmenter().flush(5_000))
        val s = segmenter()
        s.onGate(1_000, true)
        assertNull(s.flush(1_200))
    }

    @Test
    fun `flush cannot be collected twice`() {
        val s = segmenter()
        s.onGate(1_000, true)
        assertNotNull(s.flush(5_000))
        assertNull(s.flush(6_000))
    }

    // ── What this replaced ───────────────────────────────────────────────────────────────────

    @Test
    fun `a transmission straddling an eight second boundary stays whole`() {
        // The old loop cut the stream every 8 s on a timer. A call from 7.5 s to 11 s came out
        // as two partial transcripts, and "descend and maintain five thousand" split across the
        // seam matched nothing downstream. One gate opening is now one span regardless of where
        // the clock happens to be.
        val span = transmission(7_500, 11_000)!!
        assertEquals(7_100, span.startMs)
        assertEquals(11_600, span.endMs)
    }
}
