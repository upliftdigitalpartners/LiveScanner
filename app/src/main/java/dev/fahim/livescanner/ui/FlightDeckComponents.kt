package dev.fahim.livescanner.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fahim.livescanner.ui.theme.B612Mono
import dev.fahim.livescanner.ui.theme.FdDim
import dev.fahim.livescanner.ui.theme.FdTracking
import dev.fahim.livescanner.ui.theme.FdType
import dev.fahim.livescanner.ui.theme.FlightDeck
import kotlinx.coroutines.delay

/** Tiny all-caps label above a group of controls. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text.uppercase(),
        modifier = modifier,
        fontFamily = B612Mono,
        fontSize = FdType.sectionLabel,
        letterSpacing = FdTracking.section,
        color = color ?: FlightDeck.textFaint,
    )
}

/** Body copy in the panel's default voice. */
@Composable
fun PanelText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    bold: Boolean = false,
    size: androidx.compose.ui.unit.TextUnit = FdType.body,
    tracking: androidx.compose.ui.unit.TextUnit = FdTracking.control,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text,
        modifier = modifier,
        fontFamily = B612Mono,
        fontSize = size,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        letterSpacing = tracking,
        color = color ?: FlightDeck.text,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The standard card: panel fill, 1px stroke, 8px radius. */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    fill: Color? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(FdDim.radiusCard))
            .background(fill ?: FlightDeck.panel)
            .border(1.dp, borderColor ?: FlightDeck.stroke, RoundedCornerShape(FdDim.radiusCard))
            .padding(FdDim.cardPaddingTight),
        content = content,
    )
}

/** Header on every screen but Home: back chevron, title, sub-line, optional trailing control. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = FdDim.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Text(
                "‹",
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(end = 12.dp),
                fontFamily = B612Mono,
                fontSize = FdType.ident,
                color = FlightDeck.textDim,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title.uppercase(),
                fontFamily = B612Mono,
                fontSize = FdType.screenTitle,
                fontWeight = FontWeight.Bold,
                letterSpacing = FdTracking.title,
                color = FlightDeck.textHi,
            )
            SectionLabel(subtitle, Modifier.padding(top = 3.dp))
        }
        trailing?.invoke()
    }
}

/** Accent roles a control can take. */
enum class FdAccent { CYAN, GREEN, AMBER, MAGENTA, RED, NEUTRAL }

@Composable
private fun accentTriple(accent: FdAccent): Triple<Color, Color, Color> {
    val p = FlightDeck
    return when (accent) {
        FdAccent.CYAN -> Triple(p.cyan, p.cyanBorder, p.cyanBg)
        FdAccent.GREEN -> Triple(p.green, p.greenBorder, p.greenBg)
        FdAccent.AMBER -> Triple(p.amber, p.amberBorder, Color.Transparent)
        FdAccent.MAGENTA -> Triple(p.magenta, p.magentaBorder, Color.Transparent)
        FdAccent.RED -> Triple(p.red, p.redBorder, Color.Transparent)
        FdAccent.NEUTRAL -> Triple(p.textFaint, p.stroke, Color.Transparent)
    }
}

/**
 * The workhorse control: an outlined key that lights up in its accent when active.
 * Used for the system key row, the CC/EN/FLW chips, range keys, EQ presets and soft keys.
 */
@Composable
fun FdKey(
    label: String,
    active: Boolean,
    accent: FdAccent,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val (fg, border, fill) = accentTriple(accent)
    val p = FlightDeck
    Box(
        modifier
            .clip(RoundedCornerShape(FdDim.radiusControl))
            .background(if (active) fill else Color.Transparent)
            .border(
                1.dp,
                if (active) border else p.strokeDim,
                RoundedCornerShape(FdDim.radiusControl),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = FdDim.controlPaddingV),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            fontFamily = B612Mono,
            fontSize = FdType.control,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = FdTracking.control,
            color = when {
                !enabled -> p.textGhost
                active -> fg
                else -> p.textFaint
            },
            maxLines = 1,
        )
    }
}

/** Small status chip, e.g. "30 MIN BUFFER" or "TEST FIRE". */
@Composable
fun FdChip(
    label: String,
    accent: FdAccent,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val (fg, border, fill) = accentTriple(accent)
    Box(
        modifier
            .clip(RoundedCornerShape(FdDim.radiusChip))
            .background(fill)
            .border(1.dp, border, RoundedCornerShape(FdDim.radiusChip))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Text(
            label.uppercase(),
            fontFamily = B612Mono,
            fontSize = FdType.control,
            letterSpacing = FdTracking.control,
            color = fg,
            maxLines = 1,
        )
    }
}

/** The 4-character feed code chip: cyan for ATC, magenta for scanner feeds. */
@Composable
fun CodeChip(code: String, accent: FdAccent, modifier: Modifier = Modifier) {
    val (fg, border, _) = accentTriple(accent)
    Box(
        modifier
            .clip(RoundedCornerShape(FdDim.radiusChip))
            .border(1.dp, border, RoundedCornerShape(FdDim.radiusChip))
            .padding(horizontal = 7.dp, vertical = 5.dp),
    ) {
        Text(
            code.uppercase(),
            fontFamily = B612Mono,
            fontSize = FdType.control,
            fontWeight = FontWeight.Bold,
            letterSpacing = FdTracking.control,
            color = fg,
            maxLines = 1,
        )
    }
}

/**
 * VU meter. Bars scale between 15% and full height on staggered per-bar periods; when the feed is
 * paused every bar freezes flat and the whole meter drops to 25% opacity.
 */
@Composable
fun VuBars(
    playing: Boolean,
    modifier: Modifier = Modifier,
    bars: Int = 10,
    barWidth: androidx.compose.ui.unit.Dp = 4.dp,
    barHeight: androidx.compose.ui.unit.Dp = 26.dp,
    color: Color? = null,
) {
    val tint = color ?: FlightDeck.green

    // One Canvas driven by the frame clock, not one animation per bar. The clock is read inside the
    // draw lambda, so a frame invalidates drawing only — it never recomposes anything.
    var frameMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) withFrameNanos { frameMs = it / 1_000_000L }
    }

    Canvas(modifier.height(barHeight)) {
        val gap = 2.dp.toPx()
        val w = barWidth.toPx()
        val full = size.height
        for (index in 0 until bars) {
            val x = index * (w + gap)
            if (x + w > size.width) break
            // Per-bar periods of 300-600 ms, alternating phase so the meter never marches in step.
            val fraction = if (playing) {
                val period = 300 + (index * 37) % 300
                val phase = (frameMs % (period * 2L)) / period.toFloat()
                val triangle = if (phase <= 1f) phase else 2f - phase
                val flipped = if (index % 2 == 0) triangle else 1f - triangle
                0.15f + 0.85f * flipped
            } else {
                0.15f
            }
            val h = full * fraction
            drawRect(
                color = tint.copy(alpha = if (playing) 1f else 0.25f),
                topLeft = Offset(x, full - h),
                size = Size(w, h),
            )
        }
    }
}

/** Three-bar signal staircase (4/7/10 px, scaled) shown on every feed row. */
@Composable
fun SignalStaircase(strength: Int, modifier: Modifier = Modifier, color: Color? = null) {
    val tint = color ?: FlightDeck.green
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(5.dp, 9.dp, 13.dp).forEachIndexed { index, h ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(h)
                    .background(if (index < strength) tint else FlightDeck.strokeDim),
            )
        }
    }
}

/** 38x20 pill switch (scaled): the knob travels in 180 ms and the track fills green when armed. */
@Composable
fun PillSwitch(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val p = FlightDeck
    val knobOffset by animateDpAsState(
        targetValue = if (on) 26.dp else 3.dp,
        animationSpec = tween(180),
        label = "knob",
    )
    Box(
        modifier
            .size(width = 49.dp, height = 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (on) p.greenBg else p.panelAlt)
            .border(1.dp, if (on) p.greenBorder else p.strokeDim, RoundedCornerShape(13.dp))
            .clickable(onClick = onToggle),
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .align(Alignment.CenterStart)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (on) p.green else p.textGhost),
        )
    }
}

/** 3px track with a 14px square thumb (scaled), dragged by tapping anywhere along the track. */
@Composable
fun FdSlider(
    value: Int,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: FdAccent = FdAccent.CYAN,
) {
    val (fg, _, _) = accentTriple(accent)
    val p = FlightDeck
    androidx.compose.material3.Slider(
        value = value.toFloat(),
        onValueChange = { onValue(it.toInt()) },
        valueRange = 0f..100f,
        modifier = modifier,
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = fg,
            activeTrackColor = fg,
            inactiveTrackColor = p.strokeInput,
        ),
    )
}

/** A caption that types itself in over [durationMs], the way the decode strip reveals a line. */
@Composable
fun TypewriterText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    size: androidx.compose.ui.unit.TextUnit = FdType.body,
    durationMs: Long = 3_200L,
) {
    var revealed by remember(text) { mutableIntStateOf(0) }
    LaunchedEffect(text) {
        revealed = 0
        if (text.isEmpty()) return@LaunchedEffect
        val step = (durationMs / text.length).coerceAtLeast(8L)
        while (revealed < text.length) {
            delay(step)
            revealed++
        }
    }
    PanelText(
        text.take(revealed),
        modifier = modifier,
        color = color ?: FlightDeck.text,
        size = size,
    )
}

/** Pulsing dot: green when receiving, amber when the feed is up but paused. */
@Composable
fun StatusDot(live: Boolean, modifier: Modifier = Modifier) {
    val p = FlightDeck
    var frameMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        while (true) withFrameNanos { frameMs = it / 1_000_000L }
    }
    Canvas(modifier.size(9.dp)) {
        // Alpha is computed in the draw pass, so the pulse never recomposes its parent row.
        val alpha = if (live) {
            val phase = (frameMs % 2_600L) / 1_300f
            val triangle = if (phase <= 1f) phase else 2f - phase
            0.35f + 0.65f * triangle
        } else {
            1f
        }
        drawCircle(
            color = (if (live) p.green else p.amber).copy(alpha = alpha),
            radius = size.minDimension / 2f,
        )
    }
}

/** The red priority banner: drops in from -14px, auto-hides, tap or DISMISS to clear. */
@Composable
fun AlertBanner(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = FlightDeck
    val drop by animateFloatAsState(targetValue = 1f, animationSpec = tween(250), label = "drop")
    Row(
        modifier
            .fillMaxWidth()
            .offset(y = (-14).dp * (1f - drop))
            .alpha(drop)
            .clip(RoundedCornerShape(FdDim.radiusCard))
            .background(Color(0xF51E0707))
            .border(1.dp, p.redBorder, RoundedCornerShape(FdDim.radiusCard))
            .clickable(onClick = onDismiss)
            .padding(FdDim.cardPaddingTight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(38.dp)
                .background(p.red),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            PanelText(title, color = p.textHi, bold = true, size = FdType.rowTitle, maxLines = 1)
            PanelText(body, color = p.text, size = FdType.control, maxLines = 2)
        }
        Text(
            "DISMISS",
            modifier = Modifier
                .clickable(onClick = onDismiss)
                .padding(start = 8.dp),
            fontFamily = B612Mono,
            fontSize = FdType.control,
            letterSpacing = FdTracking.control,
            color = p.red,
        )
    }
}
