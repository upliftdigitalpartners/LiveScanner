package dev.fahim.livescanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fahim.livescanner.data.Priority
import dev.fahim.livescanner.data.Transmission
import dev.fahim.livescanner.ui.theme.FdDim
import dev.fahim.livescanner.ui.theme.FdType
import dev.fahim.livescanner.ui.theme.FlightDeck
import kotlinx.coroutines.delay
import java.io.File

/** Bars in a transmission's waveform — matches [Transmission.waveform]. */
private const val WAVE_BARS = 34
private val WAVE_BAR_WIDTH = 3.dp
private val WAVE_BAR_HEIGHT = 18.dp

/** Collapsed cards show the waveform at just over half height, so the list reads as one texture. */
private const val WAVE_COLLAPSED_SCALE = 0.55f

/** Callsign on an emergency card: warm red, distinct from the palette's alarm red. */
private val EMERGENCY_CALLSIGN = Color(0xFFFF6B60)
private val TRANSCRIPT_TEXT = Color(0xFFE8D9F2)
private val PARAPHRASE_RULE = Color(0xFF2A1A3D)

private const val CLIP_CONFIRM_MS = 2_000L

/**
 * Flight recorder: the rolling 30-minute log of captured transmissions.
 *
 * Every card carries its own waveform and a priority rule down the left edge; tapping one expands
 * it — one at a time — to reveal the raw transcript, the plain-English read, and the replay/export
 * controls. Nothing on screen animates while the feed is stopped.
 */
@Composable
fun HistoryScreen(vm: MainViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val history by vm.history.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()

    val p = FlightDeck
    val icao = playback.feed?.displayCode ?: "NO FEED"

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg),
    ) {
        ScreenHeader(
            title = "Flight Recorder",
            subtitle = history.filterCallsign
                ?.let { "${history.visible.size} transmissions · $it" }
                ?: "${history.transmissions.size} transmissions · $icao",
            onBack = onBack,
            trailing = { FdChip("30 min buffer", FdAccent.CYAN) },
        )

        // Filter row: tap a callsign to follow one aircraft through the log, tap again to clear.
        if (history.callsigns.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = FdDim.gutter, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FdKey(
                    label = "ALL",
                    active = history.filterCallsign == null,
                    accent = FdAccent.NEUTRAL,
                ) { vm.filterHistory(null) }
                history.callsigns.forEach { callsign ->
                    FdKey(
                        label = callsign,
                        active = history.filterCallsign.equals(callsign, ignoreCase = true),
                        accent = FdAccent.AMBER,
                    ) { vm.filterHistory(callsign) }
                }
            }
        }

        if (history.visible.isEmpty()) {
            EmptyRecorder(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = FdDim.gutter,
                    end = FdDim.gutter,
                    top = 4.dp,
                    bottom = FdDim.gutter,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(history.visible, key = { it.id }) { entry ->
                    TransmissionCard(
                        entry = entry,
                        expanded = entry.id == history.expandedId,
                        replayPct = if (entry.id == history.expandedId) history.replayPct else 0,
                        playing = playback.isPlaying,
                        onToggle = { vm.expandTransmission(entry.id) },
                        onReplay = { vm.replay(entry.id) },
                        onExport = { vm.exportClip(entry.id) },
                    )
                }
            }
        }

        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun EmptyRecorder(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = FdDim.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("Recorder empty")
            PanelText(
                "TRANSMISSIONS APPEAR HERE ONCE CAPTIONS ARE ENABLED AND THE FEED IS PLAYING.",
                color = FlightDeck.textGhost,
                size = FdType.control,
            )
        }
    }
}

@Composable
private fun TransmissionCard(
    entry: Transmission,
    expanded: Boolean,
    replayPct: Int,
    playing: Boolean,
    onToggle: () -> Unit,
    onReplay: () -> Unit,
    onExport: () -> File?,
) {
    val p = FlightDeck
    val rule = when (entry.priority) {
        Priority.EMERGENCY -> p.red
        Priority.NOTABLE -> p.cyan
        Priority.ROUTINE -> p.stroke
    }

    PanelCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FdDim.radiusCard))
            .clickable(onClick = onToggle),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(rule),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PanelText(
                        entry.clockLabel,
                        color = p.textDim,
                        size = FdType.control,
                        maxLines = 1,
                    )
                    PanelText(
                        entry.callsign?.uppercase() ?: "UNIDENT",
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                        color = if (entry.priority == Priority.EMERGENCY) {
                            EMERGENCY_CALLSIGN
                        } else {
                            p.textHi
                        },
                        bold = true,
                        size = FdType.rowTitle,
                        maxLines = 1,
                    )
                    PanelText(
                        "${entry.feedLabel.uppercase()} · ${entry.durationLabel}",
                        color = p.textFaint,
                        size = FdType.control,
                        maxLines = 1,
                    )
                }

                Waveform(
                    amplitudes = entry.waveform,
                    expanded = expanded,
                    replayPct = replayPct,
                    playing = playing,
                )

                if (expanded) {
                    PanelText(entry.raw.uppercase(), color = TRANSCRIPT_TEXT, size = FdType.control)

                    entry.plainEnglish?.let { plain ->
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(PARAPHRASE_RULE),
                            )
                            PanelText(
                                plain.uppercase(),
                                modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                                color = p.textDim,
                                size = FdType.paraphrase,
                            )
                        }
                    }

                    ReplayControls(
                        durationLabel = entry.durationLabel,
                        replayPct = replayPct,
                        playing = playing,
                        // A clip that has aged out of the rolling buffer can no longer be played.
                        armed = entry.bufferOffset != null,
                        onReplay = onReplay,
                        onExport = onExport,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReplayControls(
    durationLabel: String,
    replayPct: Int,
    playing: Boolean,
    armed: Boolean,
    onReplay: () -> Unit,
    onExport: () -> File?,
) {
    val p = FlightDeck
    var clipLabel by remember { mutableStateOf<String?>(null) }

    // The confirmation is the whole export UI — the key reverts on its own.
    LaunchedEffect(clipLabel) {
        if (clipLabel != null) {
            delay(CLIP_CONFIRM_MS)
            clipLabel = null
        }
    }

    val progress by animateFloatAsState(
        targetValue = replayPct.coerceIn(0, 100) / 100f,
        animationSpec = if (playing) tween<Float>(140, easing = LinearEasing) else snap(),
        label = "replayProgress",
    )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FdKey(
            label = "Replay",
            active = replayPct > 0,
            accent = FdAccent.GREEN,
            enabled = armed,
            onClick = onReplay,
        )
        Box(
            Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(p.strokeInput),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(p.green),
            )
        }
        PanelText(durationLabel, color = p.textFaint, size = FdType.control, maxLines = 1)
        FdKey(
            label = clipLabel ?: "Clip",
            active = clipLabel != null,
            accent = FdAccent.AMBER,
            enabled = armed,
            onClick = { clipLabel = if (onExport() != null) "Saved" else "No clip" },
        )
    }
}

/**
 * The card's waveform: 34 bars that grow to full height when the card opens. Once expanded, the
 * span already replayed reads green and the rest stays in the stroke color.
 */
@Composable
private fun Waveform(
    amplitudes: List<Float>,
    expanded: Boolean,
    replayPct: Int,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val p = FlightDeck
    val scale by animateFloatAsState(
        targetValue = if (expanded) 1f else WAVE_COLLAPSED_SCALE,
        animationSpec = if (playing) tween<Float>(200) else snap(),
        label = "waveScale",
    )
    val playedBars = if (expanded) WAVE_BARS * replayPct.coerceIn(0, 100) / 100 else 0
    val played = p.green
    val idle = p.stroke

    // One Canvas rather than 34 Boxes: a list of these was contributing a couple of hundred
    // layout nodes to the recorder's scroll for no benefit.
    Canvas(modifier.height(WAVE_BAR_HEIGHT)) {
        val gap = 1.dp.toPx()
        val barWidth = WAVE_BAR_WIDTH.toPx()
        val full = size.height
        for (index in 0 until WAVE_BARS) {
            val x = index * (barWidth + gap)
            if (x + barWidth > size.width) break
            val amplitude = amplitudes.getOrElse(index) { 0.35f }.coerceIn(0.08f, 1f)
            val h = full * amplitude * scale
            drawRect(
                color = if (index < playedBars) played else idle,
                topLeft = Offset(x, full - h),
                size = Size(barWidth, h),
            )
        }
    }
}
