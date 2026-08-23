package dev.fahim.livescanner.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** EQ voicings offered by the audio panel. */
enum class EqPreset(val label: String, val blurb: String) {
    VOICE("VOICE", "Mid-band lift around 1-3 kHz. Best for garbled tower audio."),
    FLAT("FLAT", "No shaping — the feed exactly as it arrives."),
    LOW_CUT("LOW-CUT", "Rolls off rumble below 300 Hz."),
    NARROW("NARROW", "Aggressive 300 Hz to 3 kHz band pass — classic comm radio."),
    ;

    companion object {
        fun fromKey(key: String): EqPreset = entries.firstOrNull { it.label == key } ?: VOICE
    }
}

/** One direct-form-1 biquad section. */
private class Biquad {
    var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    fun setHighPass(sampleRate: Int, freq: Double, q: Double) {
        val w0 = 2.0 * PI * freq / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW = cos(w0)
        val a0 = 1.0 + alpha
        b0 = ((1.0 + cosW) / 2.0) / a0
        b1 = (-(1.0 + cosW)) / a0
        b2 = ((1.0 + cosW) / 2.0) / a0
        a1 = (-2.0 * cosW) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setLowPass(sampleRate: Int, freq: Double, q: Double) {
        val w0 = 2.0 * PI * freq / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW = cos(w0)
        val a0 = 1.0 + alpha
        b0 = ((1.0 - cosW) / 2.0) / a0
        b1 = (1.0 - cosW) / a0
        b2 = ((1.0 - cosW) / 2.0) / a0
        a1 = (-2.0 * cosW) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setPeaking(sampleRate: Int, freq: Double, q: Double, gainDb: Double) {
        val a = Math.pow(10.0, gainDb / 40.0)
        val w0 = 2.0 * PI * freq / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW = cos(w0)
        val a0 = 1.0 + alpha / a
        b0 = (1.0 + alpha * a) / a0
        b1 = (-2.0 * cosW) / a0
        b2 = (1.0 - alpha * a) / a0
        a1 = (-2.0 * cosW) / a0
        a2 = (1.0 - alpha / a) / a0
    }

    fun setBypass() { b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0 }
}

/**
 * The comm-radio audio chain: gain, squelch gate, and EQ, plus the signal metering that the
 * audio panel's signal-versus-squelch scope draws from.
 *
 * Runs on 16-bit PCM inside ExoPlayer's audio pipeline, so it shapes exactly what you hear.
 */
class FlightDeckDsp : BaseAudioProcessor() {

    @Volatile var gain: Int = 50              // 0..100, 50 = unity

    /** The squelch gate lives in its own class so it can be tested; see [NoiseGate]. */
    private val gate = NoiseGate()

    var squelch: Int
        get() = gate.squelch
        set(value) { gate.squelch = value }

    var gateEnabled: Boolean
        get() = gate.enabled
        set(value) { gate.enabled = value }

    /**
     * TRIM SILENCE is a *recorder* setting — "skip dead air in the 30-minute buffer". It is
     * deliberately not applied to live output: dropping frames from a live stream runs words
     * together and starves the audio sink, which is heard as the feed cutting in and out.
     */
    @Volatile var trimSilence: Boolean = true
    @Volatile var preset: EqPreset = EqPreset.VOICE
        set(value) { field = value; presetDirty = true }

    private val _level = MutableStateFlow(0f)

    /** Instantaneous signal level, 0..1, sampled by the squelch scope. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _gateOpen = MutableStateFlow(false)
    val gateOpen: StateFlow<Boolean> = _gateOpen.asStateFlow()

    private var sampleRate = 44_100
    private var channels = 2
    private var presetDirty = true
    private val eqA = Biquad()
    private val eqB = Biquad()

    /** Reused sample scratch — the audio path must not allocate per buffer. */
    private var scratch = ShortArray(0)

    private var lastPublishMs = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        gate.configure(sampleRate)
        presetDirty = true
        eqA.reset()
        eqB.reset()
        return inputAudioFormat
    }

    private fun applyPreset() {
        when (preset) {
            EqPreset.FLAT -> { eqA.setBypass(); eqB.setBypass() }
            EqPreset.VOICE -> {
                eqA.setPeaking(sampleRate, 2_000.0, 0.9, 6.0)
                eqB.setHighPass(sampleRate, 180.0, 0.707)
            }
            EqPreset.LOW_CUT -> {
                eqA.setHighPass(sampleRate, 300.0, 0.707)
                eqB.setBypass()
            }
            EqPreset.NARROW -> {
                eqA.setHighPass(sampleRate, 300.0, 0.9)
                eqB.setLowPass(sampleRate, 3_000.0, 0.9)
            }
        }
        eqA.reset()
        eqB.reset()
        presetDirty = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (presetDirty) applyPreset()

        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val input = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()

        val sampleCount = input.remaining()
        if (scratch.size < sampleCount) scratch = ShortArray(sampleCount)
        val shorts = scratch
        input.get(shorts, 0, sampleCount)

        // 50 = unity, 100 = +24 dB, 0 = -24 dB.
        val gainDb = (gain - 50) / 50.0 * 24.0
        val gainLinear = Math.pow(10.0, gainDb / 20.0)
        val step = if (channels > 0) channels else 1

        var sumSquares = 0.0
        var frames = 0
        var open = false
        var written = 0

        var i = 0
        while (i + step <= sampleCount) {
            // Level and gating decisions are made on the frame, not per channel.
            var frameSum = 0.0
            for (c in 0 until step) frameSum += shorts[i + c] / 32768.0
            val mono = frameSum / step
            val magnitude = if (mono < 0) -mono else mono
            val multiplier = gate.process(magnitude)
            if (multiplier > 0.5) open = true

            for (c in 0 until step) {
                var sample = shorts[i + c] / 32768.0
                sample = eqB.process(eqA.process(sample))
                sample *= gainLinear * multiplier
                if (sample > 1.0) sample = 1.0
                if (sample < -1.0) sample = -1.0
                shorts[written + c] = (sample * 32767.0).toInt().toShort()
            }
            written += step

            sumSquares += mono * mono
            frames++
            i += step
        }

        val out = replaceOutputBuffer(written * 2).order(ByteOrder.nativeOrder())
        for (s in 0 until written) out.putShort(shorts[s])
        inputBuffer.position(inputBuffer.limit())
        out.flip()

        // Publish metering at 20 Hz, not once per buffer. These are StateFlows the UI collects, and
        // at buffer rate they drive far more recomposition than a moving meter can even show.
        if (frames > 0) {
            val now = System.currentTimeMillis()
            if (now - lastPublishMs >= PUBLISH_INTERVAL_MS) {
                lastPublishMs = now
                val rms = sqrt(sumSquares / frames)
                // ATC audio sits low in the scale; scale so normal speech lands mid-meter.
                _level.value = (rms * 4.0).coerceIn(0.0, 1.0).toFloat()
                _gateOpen.value = open
            }
        }
    }

    override fun onFlush() {
        eqA.reset()
        eqB.reset()
        gate.reset()
        _level.value = 0f
        _gateOpen.value = false
    }

    override fun onReset() {
        onFlush()
    }

    private companion object {
        const val PUBLISH_INTERVAL_MS = 50L // 20 Hz
    }
}
