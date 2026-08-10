package dev.fahim.livescanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fahim.livescanner.playback.EqPreset
import dev.fahim.livescanner.ui.theme.FdDim
import dev.fahim.livescanner.ui.theme.FdType
import dev.fahim.livescanner.ui.theme.FlightDeck
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SCOPE_BARS = 40

/** Height every bar collapses to when the feed is paused, as a fraction of the scope. */
private const val IDLE_FRACTION = 0.18f

private const val TWO_PI = 6.2831855f

/** Radians per second of the slowest bar group; the others run at 2x and 3x this. */
private const val BREATH_RATE = 2.4f

private val SCOPE_HEIGHT = 68.dp

/** Bars sitting below the squelch threshold — dark green, still legible as "there but gated". */
private val GATE_FLOOR = Color(0xFF1A2B1F)

/**
 * Fixed per-bar weight in 0..1. Hashed off the index rather than randomised so the spectrum keeps
 * the same silhouette across recompositions and process restarts.
 */
private val BAR_SEEDS = FloatArray(SCOPE_BARS) { index ->
    val x = sin(index * 12.9898f + 4.1414f) * 43758.545f
    x - floor(x)
}

/**
 * Audio panel: the squelch scope, gain/squelch trims, EQ presets and the three DSP toggles.
 *
 * Every control writes straight through to the live DSP chain via the view model, so the scope at
 * the top is already showing the effect of the sliders underneath it.
 */
@Composable
fun AudioScreen(vm: MainViewModel, onBack: () -> Unit) {
    val audio by vm.audio.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val night by vm.night.collectAsStateWithLifecycle()
    // Metering is collected here rather than in the panel state — it moves at 20 Hz, and only the
    // scope below cares. Everything else on this screen changes when you touch a control.
    val level by vm.signalLevel.collectAsStateWithLifecycle()
    val gateOpen by vm.gateOpen.collectAsStateWithLifecycle()
    val p = FlightDeck

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = "AUDIO PANEL",
            subtitle = "COMM 1 · ${playback.feed?.displayCode ?: "—"}",
            onBack = onBack,
        ) {
            FdChip(
                label = if (night) "NIGHT" else "DAY",
                accent = if (night) FdAccent.RED else FdAccent.NEUTRAL,
                onClick = vm::toggleNight,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = FdDim.gutter)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SquelchScope(
                level = level,
                squelch = audio.squelch,
                gateOpen = gateOpen,
                playing = playback.isPlaying,
            )

            TrimRow(
                label = "GAIN",
                value = audio.gain,
                readout = gainDb(audio.gain),
                onValue = vm::setGain,
            )
            TrimRow(
                label = "SQUELCH",
                value = audio.squelch,
                readout = "${audio.squelch}%",
                onValue = vm::setSquelch,
                accent = FdAccent.AMBER,
            )

            EqPresets(active = audio.eq, onPick = vm::setEq)

            DspToggle(
                name = "NOISE GATE",
                description = "Mute the hiss between transmissions",
                on = audio.noiseGate,
                onToggle = vm::toggleNoiseGate,
            )
            DspToggle(
                name = "TRIM SILENCE",
                description = "Skip dead air in the 30-minute buffer",
                on = audio.trimSilence,
                onToggle = vm::toggleTrimSilence,
            )
            DspToggle(
                name = "DUCK FOR NAV",
                description = "Lower feed when turn-by-turn speaks",
                on = audio.duckForNav,
                onToggle = vm::toggleDuckForNav,
            )
        }
    }
}

/** 0..100 on the slider maps to -24..+24 dB, centred at 50. */
private fun gainDb(gain: Int): String {
    val db = ((gain - 50) * 0.48f).roundToInt()
    return if (db >= 0) "+$db dB" else "$db dB"
}

/**
 * Live spectrum against the squelch threshold. Bars above the threshold are green, bars below sit
 * in the gated dark; the amber hairline is where the gate opens. Paused, the whole scope flattens.
 */
@Composable
private fun SquelchScope(level: Float, squelch: Int, gateOpen: Boolean, playing: Boolean) {
    val p = FlightDeck
    val live = p.green
    val threshLine = p.amber

    // One phase advanced per frame, read inside the draw lambda so motion invalidates drawing
    // only — the bars never trigger a recomposition, and nothing is allocated per frame.
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            phase = (phase + (now - last) / 1_000_000_000f * BREATH_RATE) % TWO_PI
            last = now
        }
    }

    PanelCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("SIGNAL VS SQUELCH GATE", Modifier.weight(1f))
            PanelText("SQ $squelch", color = p.amber, size = FdType.control, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            PanelText(
                if (gateOpen) "OPEN" else "CLOSED",
                color = if (gateOpen) p.green else p.textFaint,
                bold = true,
                size = FdType.control,
                maxLines = 1,
            )
        }

        val amplitude = level.coerceIn(0f, 1f)
        val threshold = squelch / 100f
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(SCOPE_HEIGHT),
        ) {
            val gap = 2.dp.toPx()
            val barWidth = ((size.width - gap * (SCOPE_BARS - 1)) / SCOPE_BARS).coerceAtLeast(1f)
            for (i in 0 until SCOPE_BARS) {
                val seed = BAR_SEEDS[i]
                val fraction = if (!playing) {
                    IDLE_FRACTION
                } else {
                    // Three whole-multiple rates keep the phase wrappable at 2π; the per-bar
                    // offset stops the groups moving as one block.
                    val breath = 0.55f + 0.45f * sin(phase * (1 + i % 3) + seed * TWO_PI)
                    (amplitude * (0.55f + 0.9f * seed) * breath).coerceIn(IDLE_FRACTION, 1f)
                }
                val top = size.height * (1f - fraction)
                drawRect(
                    color = if (fraction >= threshold) live else GATE_FLOOR,
                    topLeft = Offset(i * (barWidth + gap), top),
                    size = Size(barWidth, size.height - top),
                )
            }
            val hairline = 1.dp.toPx()
            drawRect(
                color = threshLine,
                topLeft = Offset(0f, (size.height * (1f - threshold)).coerceIn(0f, size.height - hairline)),
                size = Size(size.width, hairline),
            )
        }
    }
}

/** A labelled slider with its value spelled out to the right of the track. */
@Composable
private fun TrimRow(
    label: String,
    value: Int,
    readout: String,
    onValue: (Int) -> Unit,
    accent: FdAccent = FdAccent.CYAN,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(label)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FdSlider(value = value, onValue = onValue, modifier = Modifier.weight(1f), accent = accent)
            PanelText(
                readout,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .width(62.dp),
                color = FlightDeck.textHi,
                bold = true,
                size = FdType.control,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EqPresets(active: EqPreset, onPick: (EqPreset) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("EQ PRESET", Modifier.padding(bottom = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EqPreset.entries.forEach { preset ->
                FdKey(
                    label = preset.label,
                    active = preset == active,
                    accent = if (preset == active) FdAccent.CYAN else FdAccent.NEUTRAL,
                    modifier = Modifier.weight(1f),
                    onClick = { onPick(preset) },
                )
            }
        }
        PanelText(
            active.blurb,
            modifier = Modifier.padding(top = 8.dp),
            color = FlightDeck.textFaint,
            size = FdType.control,
        )
    }
}

/** Name and one-line rationale on the left, hard ON/OFF state on the right; the card is the hit area. */
@Composable
private fun DspToggle(name: String, description: String, on: Boolean, onToggle: () -> Unit) {
    val p = FlightDeck
    PanelCard(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PanelText(name, color = p.textHi, bold = true, size = FdType.rowTitle, maxLines = 1)
                PanelText(
                    description,
                    modifier = Modifier.padding(top = 3.dp),
                    color = p.textFaint,
                    size = FdType.control,
                    maxLines = 2,
                )
            }
            PanelText(
                if (on) "ON" else "OFF",
                modifier = Modifier.padding(start = 12.dp),
                color = if (on) p.green else p.textGhost,
                bold = true,
                size = FdType.control,
                maxLines = 1,
            )
        }
    }
}
