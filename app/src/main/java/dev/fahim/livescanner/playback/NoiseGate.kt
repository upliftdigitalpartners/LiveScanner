package dev.fahim.livescanner.playback

/**
 * The squelch gate: mutes the hiss between transmissions without eating the transmissions.
 *
 * Deliberately its own class rather than inline in the audio processor, because a gate is easy to
 * get subtly wrong in ways you can only hear — too high a threshold clips quiet speech, and too
 * short a hold makes it chatter between syllables, which sounds like the feed cutting in and out.
 * Separated out, it can be tested.
 *
 * Not thread-safe; it belongs to one audio thread.
 */
class NoiseGate {

    /** Squelch position, 0..100, straight from the audio panel. 0 disables gating entirely. */
    @Volatile
    var squelch: Int = 38

    @Volatile
    var enabled: Boolean = true

    private var sampleRate = 44_100
    private var holdSamples = 0
    private var rampStep = 1.0

    private var envelope = 0.0
    private var holdRemaining = 0
    private var multiplier = 1.0

    /** True while audio is passing, for the OPEN/CLOSED readout. */
    val isOpen: Boolean
        get() = multiplier > 0.5

    fun configure(sampleRate: Int) {
        this.sampleRate = sampleRate.coerceAtLeast(8_000)
        holdSamples = (this.sampleRate * HOLD_MS / 1000.0).toInt()
        // Ramp rather than switch, so closing doesn't put a click in the stream.
        rampStep = 1.0 / (this.sampleRate * RAMP_MS / 1000.0)
        reset()
    }

    fun reset() {
        envelope = 0.0
        holdRemaining = 0
        multiplier = 1.0
    }

    /**
     * Feeds one frame's magnitude (0..1) and returns the gain to apply to it.
     *
     * The threshold curve is squared: the useful range for a compressed ATC feed is all down at the
     * bottom, so a linear knob would put every usable setting in the first few percent of travel.
     */
    fun process(magnitude: Double): Double {
        if (!enabled || squelch <= 0) {
            multiplier = 1.0
            return 1.0
        }

        val position = squelch.coerceIn(0, 100) / 100.0
        val threshold = position * position * MAX_THRESHOLD

        envelope = if (magnitude > envelope) magnitude else envelope * RELEASE
        if (envelope >= threshold) {
            // Hold the gate open past the end of a word, so ordinary gaps in speech don't close it.
            holdRemaining = holdSamples
        } else if (holdRemaining > 0) {
            holdRemaining--
        }

        val target = if (holdRemaining > 0) 1.0 else 0.0
        multiplier = when {
            multiplier < target -> (multiplier + rampStep).coerceAtMost(target)
            multiplier > target -> (multiplier - rampStep).coerceAtLeast(target)
            else -> target
        }
        return multiplier
    }

    private companion object {
        /**
         * Even at full squelch the gate opens well below a tenth of full scale — ATC feeds are
         * quiet, and a threshold set for music would silence them completely.
         */
        const val MAX_THRESHOLD = 0.08

        /** Long enough to bridge the gap between words; short enough not to pass hiss. */
        const val HOLD_MS = 320.0

        /** Ramp in and out over a few milliseconds so the gate never clicks. */
        const val RAMP_MS = 6.0

        /** Per-sample envelope decay. */
        const val RELEASE = 0.9997
    }
}
