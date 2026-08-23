package dev.fahim.livescanner.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sin

/**
 * The gate shipped chattering: the threshold was set for music, there was no hold, and gated frames
 * were dropped outright. On a quiet ATC feed that closed between every word, which is heard as the
 * audio cutting in and out. These pin the behaviour that fixed it.
 */
class NoiseGateTest {

    private val rate = 44_100
    private lateinit var gate: NoiseGate

    @Before
    fun setUp() {
        gate = NoiseGate().apply { configure(rate) }
    }

    private fun ms(count: Int) = rate * count / 1000

    /** Feeds [millis] of a tone at [amplitude] and returns the average gain applied. */
    private fun feed(millis: Int, amplitude: Double): Double {
        var total = 0.0
        val samples = ms(millis)
        for (n in 0 until samples) {
            val magnitude = kotlin.math.abs(amplitude * sin(2.0 * Math.PI * 700.0 * n / rate))
            total += gate.process(magnitude)
        }
        return if (samples == 0) 1.0 else total / samples
    }

    @Test
    fun `speech at a realistic ATC level passes`() {
        // Compressed ATC audio peaks well below full scale; anything above ~5% must get through.
        val gain = feed(millis = 500, amplitude = 0.08)
        assertTrue("quiet speech was gated out, gain=$gain", gain > 0.9)
    }

    @Test
    fun `hiss between transmissions is silenced`() {
        feed(millis = 200, amplitude = 0.08) // open it first
        val gain = feed(millis = 3_000, amplitude = 0.002)
        assertTrue("background hiss was not gated, gain=$gain", gain < 0.2)
    }

    @Test
    fun `an ordinary gap between words does not close the gate`() {
        // The regression: a 150 ms pause is a gap in speech, not the end of a transmission.
        feed(millis = 300, amplitude = 0.10)
        val gain = feed(millis = 150, amplitude = 0.0)
        assertTrue("the gate chattered between words, gain=$gain", gain > 0.95)
    }

    @Test
    fun `a long silence does eventually close it`() {
        feed(millis = 300, amplitude = 0.10)
        val gain = feed(millis = 2_000, amplitude = 0.0)
        assertTrue("the gate never closed, gain=$gain", gain < 0.3)
    }

    @Test
    fun `speech after a closed gate is not clipped off the front`() {
        feed(millis = 300, amplitude = 0.10)
        feed(millis = 2_000, amplitude = 0.0) // closed
        // The gate must be fully open again within a few milliseconds of signal returning.
        val gain = feed(millis = 20, amplitude = 0.10)
        assertTrue("the start of the transmission was swallowed, gain=$gain", gain > 0.5)
    }

    @Test
    fun `the gate opens and closes smoothly rather than clicking`() {
        feed(millis = 300, amplitude = 0.10)
        // Step through the close and check no single sample jumps the full range.
        var previous = gate.process(0.0)
        var biggestJump = 0.0
        repeat(ms(1_000)) {
            val next = gate.process(0.0)
            biggestJump = maxOf(biggestJump, kotlin.math.abs(next - previous))
            previous = next
        }
        assertTrue("gain stepped discontinuously by $biggestJump", biggestJump < 0.05)
    }

    @Test
    fun `squelch at zero disables gating entirely`() {
        gate.squelch = 0
        assertEquals(1.0, feed(millis = 500, amplitude = 0.0), 1e-9)
    }

    @Test
    fun `a disabled gate always passes audio`() {
        gate.enabled = false
        assertEquals(1.0, feed(millis = 500, amplitude = 0.0), 1e-9)
    }

    @Test
    fun `raising squelch raises the level needed to open`() {
        val quiet = 0.02
        gate.squelch = 20
        gate.reset()
        val atLowSquelch = feed(millis = 400, amplitude = quiet)

        gate.squelch = 95
        gate.reset()
        val atHighSquelch = feed(millis = 400, amplitude = quiet)

        assertTrue(
            "squelch made no difference ($atLowSquelch vs $atHighSquelch)",
            atLowSquelch > atHighSquelch,
        )
    }

    @Test
    fun `even full squelch stays usable for a quiet feed`() {
        // A threshold tuned for music would silence ATC entirely at the top of the knob.
        gate.squelch = 100
        gate.reset()
        val gain = feed(millis = 500, amplitude = 0.30)
        assertTrue("loud speech was gated at full squelch, gain=$gain", gain > 0.9)
    }

    @Test
    fun `reset reopens the gate`() {
        feed(millis = 300, amplitude = 0.10)
        feed(millis = 2_000, amplitude = 0.0)
        gate.reset()
        assertTrue(gate.isOpen)
    }
}
