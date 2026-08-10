package dev.fahim.livescanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.fahim.livescanner.ui.theme.B612Mono
import dev.fahim.livescanner.ui.theme.FdTracking
import dev.fahim.livescanner.ui.theme.FdType
import dev.fahim.livescanner.ui.theme.FlightDeck
import kotlin.math.min

/** The power-on self test: what the panel checks, and what it found. */
private val CHECK_LINES = listOf(
    "ADS-B LINK" to "OK",
    "AUDIO CHAIN" to "OK",
    "BUFFER 30:00" to "READY",
    "AI DECODE" to "STANDBY",
)

// Milestones, in milliseconds from the first frame. The stages overlap on purpose: run strictly
// serially and 2.2 s reads as four separate waits instead of one instrument waking up.
private const val RING_FIRST_MS = 60f
private const val RING_STAGGER_MS = 110f
private const val RING_DRAW_MS = 320f
private const val SWEEP_START_MS = 220f
private const val SWEEP_DURATION_MS = 1_150f
private const val CHECK_FIRST_MS = 620f
private const val CHECK_STEP_MS = 180f
private const val CHECK_FADE_MS = 110f
private const val RESULT_LAG_MS = 150f
private const val WORDMARK_START_MS = 1_360f
private const val WORDMARK_FADE_MS = 340f
private const val SUBTITLE_LAG_MS = 140f
private const val FADE_OUT_MS = 250f
private const val TOTAL_MS = 2_200L

private val CHECK_BLOCK_WIDTH = 236.dp

/** 0 at [start], 1 after [duration] — the only shape any stage of this sequence needs. */
private fun ramp(ms: Long, start: Float, duration: Float): Float =
    ((ms - start) / duration).coerceIn(0f, 1f)

/**
 * Avionics power-on self test, shown over the app once at launch and then gone.
 *
 * Four scope rings draw themselves outward, a sweep makes one rotation, the self test lines report
 * in, the wordmark comes up, and the whole panel dims out — about 2.2 s, or however long it takes
 * the user to tap. [onFinished] fires exactly once either way.
 *
 * One frame clock drives everything. It is read only inside draw and `graphicsLayer` lambdas, so a
 * frame invalidates drawing and nothing else: this screen never recomposes while it plays.
 */
@Composable
fun BootSequence(onFinished: () -> Unit) {
    val p = FlightDeck

    var elapsedMs by remember { mutableLongStateOf(0L) }

    // Held outside composition state reads so neither the tap nor the timeout can fire twice, and
    // so a late-arriving [onFinished] is the one that gets called.
    val fired = remember { mutableStateOf(false) }
    val latestOnFinished = rememberUpdatedState(onFinished)
    val finish = {
        if (!fired.value) {
            fired.value = true
            latestOnFinished.value()
        }
    }

    LaunchedEffect(Unit) {
        val startNanos = withFrameNanos { it }
        var running = true
        while (running) {
            withFrameNanos { frameNanos ->
                val ms = (frameNanos - startNanos) / 1_000_000L
                elapsedMs = ms
                if (ms >= TOTAL_MS) running = false
            }
        }
        finish()
    }

    // The sweep gradient builds a shader; with no explicit centre it uses the draw area's centre,
    // which is where the rings are. Hoisted so the frame loop allocates nothing.
    val sweepBrush = remember(p.green) {
        Brush.sweepGradient(
            0f to p.green.copy(alpha = 0.30f),
            (70f / 360f) to Color.Transparent,
            1f to Color.Transparent,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - ramp(elapsedMs, TOTAL_MS - FADE_OUT_MS, FADE_OUT_MS) }
            .background(p.bg)
            .pointerInput(Unit) { detectTapGestures { finish() } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ms = elapsedMs
            val centre = Offset(size.width / 2f, size.height / 2f)
            val scopeR = min(size.width, size.height) / 2f * 0.62f
            val hairline = 1.dp.toPx()

            // Each ring is an arc that closes on itself, the innermost first, so the scope builds
            // outward from the centre.
            for (ring in 1..4) {
                val progress = ramp(
                    ms,
                    RING_FIRST_MS + (ring - 1) * RING_STAGGER_MS,
                    RING_DRAW_MS,
                )
                if (progress <= 0f) continue
                val r = scopeR * ring / 4f
                drawArc(
                    color = p.cyan.copy(alpha = 0.10f + 0.04f * ring),
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(centre.x - r, centre.y - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = hairline),
                )
            }

            // One rotation of the wedge, then it is gone for good.
            val sweep = ramp(ms, SWEEP_START_MS, SWEEP_DURATION_MS)
            if (sweep > 0f && sweep < 1f) {
                rotate(degrees = -90f + 360f * sweep, pivot = centre) {
                    drawCircle(brush = sweepBrush, radius = scopeR, center = centre)
                }
            }
        }

        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // All four rows are always composed and only their opacity moves, so the reveal costs
            // no recomposition and the wordmark below never shifts as lines report in.
            Column(
                Modifier.width(CHECK_BLOCK_WIDTH),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                CHECK_LINES.forEachIndexed { index, (label, result) ->
                    val at = CHECK_FIRST_MS + index * CHECK_STEP_MS
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        PanelText(
                            label,
                            Modifier
                                .weight(1f)
                                .graphicsLayer { alpha = ramp(elapsedMs, at, CHECK_FADE_MS) },
                            color = p.textFaint,
                            size = FdType.control,
                            maxLines = 1,
                        )
                        PanelText(
                            result,
                            Modifier.graphicsLayer {
                                alpha = ramp(elapsedMs, at + RESULT_LAG_MS, CHECK_FADE_MS)
                            },
                            color = p.green,
                            size = FdType.control,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(30.dp))

            Text(
                "LIVE SCANNER",
                modifier = Modifier.graphicsLayer {
                    alpha = ramp(elapsedMs, WORDMARK_START_MS, WORDMARK_FADE_MS)
                },
                fontFamily = B612Mono,
                fontSize = FdType.wordmark,
                fontWeight = FontWeight.Bold,
                letterSpacing = FdTracking.wordmark,
                color = p.textHi,
            )
            SectionLabel(
                "FLIGHT DECK",
                Modifier
                    .padding(top = 6.dp)
                    .graphicsLayer {
                        alpha = ramp(
                            elapsedMs,
                            WORDMARK_START_MS + SUBTITLE_LAG_MS,
                            WORDMARK_FADE_MS,
                        )
                    },
            )
        }
    }
}
