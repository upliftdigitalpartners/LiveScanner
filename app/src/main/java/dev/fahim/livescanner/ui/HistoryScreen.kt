package dev.fahim.livescanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fahim.livescanner.data.Priority
import dev.fahim.livescanner.data.Transmission
import dev.fahim.livescanner.data.TranscriptWord
import dev.fahim.livescanner.ui.theme.B612Mono
import dev.fahim.livescanner.ui.theme.FdDim
import dev.fahim.livescanner.ui.theme.FdTracking
import dev.fahim.livescanner.ui.theme.FdType
import dev.fahim.livescanner.ui.theme.FlightDeck
import kotlinx.coroutines.delay
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

/** The buffer's window slides forward as audio is captured, so the scrubber re-reads its span. */
private const val BUFFER_SPAN_TICK_MS = 2_000L

private val STRIP_HEIGHT = 34.dp
private val STRIP_TICK_WIDTH = 1.5.dp

private val SPAN_CLOCK: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** m:ss, the shape [Transmission.durationLabel] uses. */
private fun mss(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** mm:ss, for the buffer strip's retained length. */
private fun mmss(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

/** One transmission's place along the buffer strip, pre-resolved so the draw pass only draws. */
private class BufferTick(val fraction: Float, val color: Color)

/** The buffer strip's end labels, measured once per span change rather than per frame. */
private class SpanLabels(val start: TextLayoutResult, val end: TextLayoutResult)

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
    val ask by vm.ask.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshBufferSpan()
            delay(BUFFER_SPAN_TICK_MS)
        }
    }

    val p = FlightDeck
    val icao = playback.feed?.displayCode ?: "NO FEED"

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            // The ask field sits at the very bottom, so the panel has to lift for the keyboard.
            .imePadding(),
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

        history.bufferSpan?.let { span ->
            BufferTimeline(
                span = span,
                transmissions = history.transmissions,
                onScrub = { vm.scrubBuffer(it) },
                modifier = Modifier.padding(horizontal = FdDim.gutter, vertical = 4.dp),
            )
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
                    val open = entry.id == history.expandedId
                    // Only the open card is handed the player's position, so a tick of replay
                    // never recomposes the rest of the list.
                    TransmissionCard(
                        entry = entry,
                        expanded = open,
                        replayPct = if (open) history.replayPct else 0,
                        positionMs = if (open) history.replayPositionMs else 0L,
                        durationMs = if (open) history.replayDurationMs else 0L,
                        playing = playback.isPlaying,
                        onToggle = { vm.expandTransmission(entry.id) },
                        onReplay = { vm.replay(entry.id) },
                        onSeekWord = { word -> vm.replayFromWord(entry.id, word) },
                        onExport = { vm.exportClip(entry.id) },
                    )
                }
            }
        }

        AskPanel(
            ask = ask,
            onAskChange = { vm.setAskQuestion(it) },
            onAsk = { vm.askTheFeed() },
            onClear = { vm.clearAsk() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FdDim.gutter, vertical = 8.dp),
        )

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
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    onToggle: () -> Unit,
    onReplay: () -> Unit,
    onSeekWord: (TranscriptWord) -> Unit,
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
                    if (entry.words.isEmpty()) {
                        PanelText(
                            entry.raw.uppercase(),
                            color = TRANSCRIPT_TEXT,
                            size = FdType.control,
                        )
                    } else {
                        TranscriptWords(entry.words, positionMs, onSeekWord)
                    }

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
                        positionMs = positionMs,
                        durationMs = durationMs,
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
    positionMs: Long,
    durationMs: Long,
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
        // The position comes from the player, so it is only trustworthy once it reports a duration.
        PanelText(
            if (durationMs > 0L) "${mss(positionMs)} / $durationLabel" else durationLabel,
            color = p.textFaint,
            size = FdType.control,
            maxLines = 1,
        )
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

/**
 * The raw transcript as individual words: the one being spoken lights amber, and tapping any word
 * jumps the audio to it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptWords(
    words: List<TranscriptWord>,
    positionMs: Long,
    onSeekWord: (TranscriptWord) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Resolved once per position tick rather than per word, so only the two words whose state
    // actually changed recompose — the rest skip.
    val active = words.indexOfFirst { positionMs >= it.startMs && positionMs <= it.endMs }

    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        words.forEachIndexed { index, word ->
            TranscriptWordChip(word, index == active, onSeekWord)
        }
    }
}

@Composable
private fun TranscriptWordChip(
    word: TranscriptWord,
    active: Boolean,
    onSeekWord: (TranscriptWord) -> Unit,
) {
    val p = FlightDeck
    Text(
        word.text.uppercase(),
        modifier = Modifier
            .clip(RoundedCornerShape(FdDim.radiusChip))
            .background(if (active) p.amberBorder else Color.Transparent)
            .clickable { onSeekWord(word) }
            .padding(horizontal = 3.dp, vertical = 1.dp),
        fontFamily = B612Mono,
        fontSize = FdType.control,
        letterSpacing = FdTracking.control,
        color = if (active) p.amber else TRANSCRIPT_TEXT,
        maxLines = 1,
    )
}

/**
 * The whole rolling window as one strip: a tick per transmission reads as activity density, and a
 * tap or drag anywhere scrubs to that instant — including the silence between recorded cards.
 */
@Composable
private fun BufferTimeline(
    span: LongRange,
    transmissions: List<Transmission>,
    onScrub: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = FlightDeck
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val spanStart = span.first
    val spanLength = (span.last - span.first).coerceAtLeast(1L)

    // Wall-clock instant last reported, or 0 until the user has scrubbed.
    var playheadMs by remember { mutableLongStateOf(0L) }

    // The span slides forward every couple of seconds. The gestures read it through these rather
    // than re-keying pointerInput, which would cancel a drag already in progress.
    val liveSpan by rememberUpdatedState(span)
    val liveScrub by rememberUpdatedState(onScrub)
    val report: (Float, Float) -> Unit = { x, width ->
        val fraction = if (width > 0f) (x / width).coerceIn(0f, 1f) else 0f
        val at = liveSpan.first + ((liveSpan.last - liveSpan.first) * fraction).toLong()
        playheadMs = at
        liveScrub(at)
    }

    val ticks = remember(transmissions, spanStart, spanLength, p) {
        transmissions.mapNotNull { entry ->
            val fraction = (entry.timestampMs - spanStart).toFloat() / spanLength
            if (fraction < 0f || fraction > 1f) {
                null
            } else {
                BufferTick(
                    fraction = fraction,
                    color = when (entry.priority) {
                        Priority.EMERGENCY -> p.red
                        Priority.NOTABLE -> p.cyan
                        Priority.ROUTINE -> p.stroke
                    },
                )
            }
        }
    }

    val labelStyle = remember(p.textGhost) {
        TextStyle(fontFamily = B612Mono, fontSize = FdType.dataTag, color = p.textGhost)
    }
    val spanEnd = span.last
    val labels = remember(spanStart, spanEnd, labelStyle) {
        val clock = { at: Long -> SPAN_CLOCK.format(Instant.ofEpochMilli(at)) }
        SpanLabels(
            start = measurer.measure(clock(spanStart), labelStyle),
            end = measurer.measure(clock(spanEnd), labelStyle),
        )
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("BUFFER · ${mmss(spanLength)} RETAINED")

        Box(
            Modifier
                .fillMaxWidth()
                .height(STRIP_HEIGHT)
                .clip(RoundedCornerShape(FdDim.radiusRow))
                .background(p.panelAlt)
                .border(1.dp, p.strokeDim, RoundedCornerShape(FdDim.radiusRow))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        report(change.position.x, size.width.toFloat())
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { at -> report(at.x, size.width.toFloat()) }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val tickWidth = STRIP_TICK_WIDTH.toPx()
                val inset = 6.dp.toPx()
                val travel = size.width - tickWidth
                for (tick in ticks) {
                    drawRect(
                        color = tick.color,
                        topLeft = Offset(tick.fraction * travel, inset),
                        size = Size(tickWidth, size.height - inset * 2f),
                    )
                }
                // Read in the draw pass, so scrubbing repaints without recomposing the strip.
                val head = playheadMs
                if (head > 0L) {
                    val fraction = (head - spanStart).toFloat() / spanLength
                    if (fraction >= 0f && fraction <= 1f) {
                        drawRect(
                            color = p.amber,
                            topLeft = Offset(fraction * travel, 0f),
                            size = Size(tickWidth, size.height),
                        )
                    }
                }
            }
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(with(density) { labels.start.size.height.toDp() }),
        ) {
            drawText(labels.start, color = p.textGhost, topLeft = Offset.Zero)
            drawText(
                labels.end,
                color = p.textGhost,
                topLeft = Offset(size.width - labels.end.size.width, 0f),
            )
        }
    }
}

/** Puts a question to Groq against the recorder log — the log only, so it can't invent traffic. */
@Composable
private fun AskPanel(
    ask: AskUiState,
    onAskChange: (String) -> Unit,
    onAsk: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = FlightDeck
    val armed = ask.question.isNotBlank() && !ask.thinking

    PanelCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(FdDim.rowPadding)) {
            SectionLabel("ASK THE FEED · GROQ", color = p.magenta)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = ask.question,
                    onValueChange = onAskChange,
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(
                        fontFamily = B612Mono,
                        fontSize = FdType.control,
                        letterSpacing = FdTracking.control,
                        color = p.textHi,
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(p.cyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onAsk() }),
                    decorationBox = { field ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(FdDim.radiusControl))
                                .background(p.panelAlt)
                                .border(
                                    1.dp,
                                    p.strokeInput,
                                    RoundedCornerShape(FdDim.radiusControl),
                                )
                                .padding(horizontal = 12.dp, vertical = FdDim.controlPaddingV),
                        ) {
                            if (ask.question.isEmpty()) {
                                PanelText(
                                    "ASK ABOUT THE LAST 30 MINUTES…",
                                    color = p.textGhost,
                                    size = FdType.control,
                                    maxLines = 1,
                                )
                            }
                            field()
                        }
                    },
                )
                FdKey(
                    label = if (ask.thinking) "…" else "ASK",
                    active = armed,
                    accent = FdAccent.MAGENTA,
                    enabled = armed,
                    onClick = onAsk,
                )
            }

            ask.answer?.let { answer ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(p.magenta),
                    )
                    PanelText(
                        answer.uppercase(),
                        modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                        color = p.text,
                        size = FdType.control,
                    )
                }
                FdKey("CLEAR", false, FdAccent.NEUTRAL) { onClear() }
            }
        }
    }
}
