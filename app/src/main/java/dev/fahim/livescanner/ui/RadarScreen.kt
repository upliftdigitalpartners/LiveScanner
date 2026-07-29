package dev.fahim.livescanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.fahim.livescanner.data.Aircraft
import dev.fahim.livescanner.data.LatLng
import dev.fahim.livescanner.data.PhotoClient
import dev.fahim.livescanner.data.friendlyType
import dev.fahim.livescanner.ui.theme.B612Mono
import dev.fahim.livescanner.ui.theme.FdDim
import dev.fahim.livescanner.ui.theme.FdTracking
import dev.fahim.livescanner.ui.theme.FdType
import dev.fahim.livescanner.ui.theme.FlightDeck
import dev.fahim.livescanner.ui.theme.altitudeRamp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SWEEP_PERIOD_SEC = 4.0
private val RANGES = listOf(10, 20, 40)
private val HEADING_TICKS = listOf("33", "34", "35", "36", "01", "02", "03", "04", "05")

/**
 * The navigation display: a scope centred on the airport you are listening to, with the traffic
 * ADS-B can see and the AI decode of what the tower just said.
 *
 * Every animation here is gated on playback — a scope that keeps sweeping while the audio is
 * paused is lying about being live.
 */
@Composable
fun RadarScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = FlightDeck
    val radar by vm.radar.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()

    val feed = playback.feed
    val center = feed?.let { f ->
        if (f.lat != null && f.lon != null) LatLng(f.lat, f.lon) else null
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .statusBarsPadding(),
    ) {
        ScreenHeader(
            title = "${feed?.displayCode ?: "— — —"} · ND",
            subtitle = "${radar.aircraft.size} CONTACTS · RNG ${radar.rangeNm}",
            onBack = onBack,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FdKey("CC", radar.captionsOn, FdAccent.CYAN) { vm.toggleCaptions() }
                    FdKey("EN", radar.plainEnglishOn, FdAccent.MAGENTA) { vm.togglePlainEnglish() }
                    FdKey("FLW", radar.followOn, FdAccent.GREEN) { vm.toggleFollow() }
                }
            },
        )

        HeadingTape()

        if (center == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                PanelText(
                    "NO SCOPE — TUNE AN AIRPORT FEED",
                    color = p.textFaint,
                    size = FdType.control,
                )
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Scope(
                    center = center,
                    radar = radar,
                    playing = playback.isPlaying,
                    onSelect = vm::selectAircraft,
                )
                CornerReadouts(Modifier.align(Alignment.TopStart).padding(FdDim.gutter))
            }
        }

        radar.selectedHex?.let { hex ->
            radar.aircraft.firstOrNull { it.hex == hex }?.let { ac ->
                SelectedBlock(ac) { vm.selectAircraft(null) }
            }
        }

        if (radar.captionsOn) {
            DecodeStrip(caption = radar.caption, plainEnglish = radar.plainEnglishOn)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = FdDim.gutter, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("RNG", Modifier.align(Alignment.CenterVertically))
            RANGES.forEach { r ->
                FdKey(
                    r.toString(),
                    active = radar.rangeNm == r,
                    accent = FdAccent.CYAN,
                    modifier = Modifier.weight(1f),
                ) { vm.setRange(r) }
            }
        }
    }
}

/** Compass tape across the top, amber index on the centre mark. */
@Composable
private fun HeadingTape() {
    val p = FlightDeck
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = FdDim.gutter)
            .height(39.dp)
            .clip(RoundedCornerShape(FdDim.radiusRow))
            .background(p.panelAlt)
            .border(1.dp, p.strokeDim, RoundedCornerShape(FdDim.radiusRow)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HEADING_TICKS.forEachIndexed { index, tick ->
                val centre = index == HEADING_TICKS.size / 2
                androidx.compose.material3.Text(
                    tick,
                    fontFamily = B612Mono,
                    fontSize = FdType.control,
                    fontWeight = if (centre) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = FdTracking.control,
                    color = if (centre) p.textHi else p.textDim,
                )
            }
        }
        androidx.compose.material3.Text(
            "HDG 360",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 1.dp),
            fontFamily = B612Mono,
            fontSize = FdType.dataTag,
            letterSpacing = FdTracking.control,
            color = p.amber,
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(1.dp)
                .height(8.dp)
                .background(p.amber),
        )
    }
}

@Composable
private fun CornerReadouts(modifier: Modifier) {
    val p = FlightDeck
    Row(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            SectionLabel("GS 000", color = p.green)
            SectionLabel("WX VFR", color = p.green)
        }
        Column(horizontalAlignment = Alignment.End) {
            SectionLabel("ADSB LIVE", color = p.cyan)
            SectionLabel("5.0S POLL", color = p.cyan)
        }
    }
}

@Composable
private fun Scope(
    center: LatLng,
    radar: RadarUiState,
    playing: Boolean,
    onSelect: (String?) -> Unit,
) {
    val p = FlightDeck
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val rangeNm = radar.rangeNm.toDouble()

    // One monotonic clock drives the sweep, the pulses and the dead-reckoning; it stops with audio.
    var nowNanos by remember { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) withFrameNanos { nowNanos = it }
    }

    val tagStyle = TextStyle(
        fontFamily = B612Mono,
        fontSize = FdType.dataTag,
        color = p.textDim,
    )

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(radar.aircraft, rangeNm) {
                detectTapGestures { tap ->
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val scopeR = min(size.width, size.height) / 2f * 0.92f
                    var best: Aircraft? = null
                    var bestD = with(density) { 30.dp.toPx() }
                    for (ac in radar.aircraft) {
                        val pos = aircraftOffset(center, ac, cx, cy, scopeR, rangeNm) ?: continue
                        val d = hypot(pos.x - tap.x, pos.y - tap.y)
                        if (d < bestD) {
                            bestD = d
                            best = ac
                        }
                    }
                    onSelect(best?.hex)
                }
            },
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val scopeR = min(size.width, size.height) / 2f * 0.92f
        val seconds = nowNanos / 1e9

        // Rings: alternating solid and dashed, cyan at 10-25% as the spec's rgba values.
        for (ring in 1..4) {
            val r = scopeR * ring / 4f
            val solid = ring % 2 == 1
            drawCircle(
                color = p.cyan.copy(alpha = if (solid) 0.25f else 0.10f),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = if (solid) null else PathEffect.dashPathEffect(floatArrayOf(6f, 8f)),
                ),
            )
        }

        // Cross hairs.
        drawLine(p.cyan.copy(alpha = 0.12f), Offset(cx, cy - scopeR), Offset(cx, cy + scopeR))
        drawLine(p.cyan.copy(alpha = 0.12f), Offset(cx - scopeR, cy), Offset(cx + scopeR, cy))

        // Range labels at full, three-quarter and half range.
        listOf(1.0f to rangeNm, 0.75f to rangeNm * 0.75, 0.5f to rangeNm * 0.5).forEach { (frac, value) ->
            drawText(
                measurer,
                value.roundToInt().toString(),
                topLeft = Offset(cx + 6f, cy - scopeR * frac),
                style = tagStyle.copy(color = p.cyan.copy(alpha = 0.45f)),
            )
        }

        // Sweep: a conic wedge, one rotation per 4 s, frozen when the audio is paused.
        val sweepDeg = if (playing) ((seconds / SWEEP_PERIOD_SEC) * 360.0 % 360.0).toFloat() else 0f
        rotate(degrees = sweepDeg, pivot = Offset(cx, cy)) {
            drawCircle(
                brush = Brush.sweepGradient(
                    0f to p.green.copy(alpha = 0.28f),
                    (80f / 360f) to Color.Transparent,
                    1f to Color.Transparent,
                    center = Offset(cx, cy),
                ),
                radius = scopeR,
                center = Offset(cx, cy),
            )
        }

        // Ownship: a 10px square outline at the field.
        val own = 13.dp.toPx() / 2f
        drawRect(
            color = p.green,
            topLeft = Offset(cx - own, cy - own),
            size = androidx.compose.ui.geometry.Size(own * 2, own * 2),
            style = Stroke(width = 1.5.dp.toPx()),
        )

        val visible = radar.aircraft.mapNotNull { ac ->
            aircraftOffset(center, ac, cx, cy, scopeR, rangeNm)?.let { ac to it }
        }

        // Trails, oldest faintest.
        for ((ac, _) in visible) {
            val trail = radar.trails[ac.hex] ?: continue
            if (trail.size < 2) continue
            val tint = targetColor(ac)
            for (i in 1 until trail.size) {
                val a = latLngToOffset(center, trail[i - 1], cx, cy, scopeR, rangeNm)
                val b = latLngToOffset(center, trail[i], cx, cy, scopeR, rangeNm)
                drawLine(tint.copy(alpha = 0.22f * i / trail.size), a, b, strokeWidth = 1.5f)
            }
        }

        for ((ac, pos) in visible) {
            val tint = targetColor(ac)

            // Lead vector: groundspeed/12 px, along the track.
            if (ac.groundSpeedKt > 5) {
                val len = (ac.groundSpeedKt / 12.0).toFloat() * (density.density / 2f)
                val rad = Math.toRadians(ac.trackDeg)
                drawLine(
                    tint.copy(alpha = 0.45f),
                    pos,
                    Offset(
                        pos.x + (sin(rad) * len).toFloat(),
                        pos.y - (cos(rad) * len).toFloat(),
                    ),
                    strokeWidth = 1.5f,
                )
            }

            // 9px diamond.
            val half = 12.dp.toPx() / 2f
            drawPath(
                Path().apply {
                    moveTo(pos.x, pos.y - half)
                    lineTo(pos.x + half, pos.y)
                    lineTo(pos.x, pos.y + half)
                    lineTo(pos.x - half, pos.y)
                    close()
                },
                color = tint,
            )

            // Data tag: callsign over flight level, trend arrow and speed.
            val callsign = ac.callsign?.trim()
            if (!callsign.isNullOrEmpty()) {
                drawText(
                    measurer,
                    callsign,
                    topLeft = Offset(pos.x + half + 4f, pos.y - half - 2f),
                    style = tagStyle.copy(color = tint.copy(alpha = 0.95f)),
                )
                val fl = ac.altitudeFt?.let { (it / 100).toString().padStart(3, '0') } ?: "GND"
                val trend = when {
                    (ac.verticalRateFpm ?: 0) > 250 -> "↑"
                    (ac.verticalRateFpm ?: 0) < -250 -> "↓"
                    else -> "·"
                }
                drawText(
                    measurer,
                    "$fl$trend ${(ac.groundSpeedKt / 10).roundToInt()}",
                    topLeft = Offset(pos.x + half + 4f, pos.y + 1f),
                    style = tagStyle.copy(color = tint.copy(alpha = 0.7f)),
                )
            }

            // Selection ring: white, pulsing 0.85-1.15 over ~1.3 s.
            if (ac.hex == radar.selectedHex) {
                val pulse = if (playing) {
                    1f + 0.15f * sin(seconds * 2.0 * Math.PI / 1.3).toFloat()
                } else {
                    1f
                }
                drawCircle(
                    Color.White,
                    radius = half * 1.9f * pulse,
                    center = pos,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            // Magenta ring on whoever is being transcribed right now.
            if (callsign != null && callsign.uppercase() in radar.transcribing) {
                val pulse = if (playing) {
                    1f + 0.15f * sin(seconds * 2.0 * Math.PI / 1.2).toFloat()
                } else {
                    1f
                }
                drawCircle(
                    p.magenta,
                    radius = half * 2.2f * pulse,
                    center = pos,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            // A flight you armed a rule for stays marked whether or not anyone is talking to it —
            // that is the whole point of tracking it.
            val isTracked = radar.tracked.any { wanted ->
                wanted.equals(callsign, ignoreCase = true) ||
                    wanted.equals(ac.registration?.trim(), ignoreCase = true)
            }
            if (isTracked) {
                drawCircle(
                    p.amber,
                    radius = half * 2.6f,
                    center = pos,
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawText(
                    measurer,
                    "TRK",
                    topLeft = Offset(pos.x - half, pos.y + half * 2.8f),
                    style = tagStyle.copy(color = p.amber),
                )
            }

            // Expanding green ping on a contact that just entered range.
            if (ac.hex == radar.newHex) {
                val phase = ((seconds % 1.1) / 1.1).toFloat()
                drawCircle(
                    p.green.copy(alpha = 1f - phase),
                    radius = half * (0.2f + phase * 2.2f),
                    center = pos,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}

/** Diamonds take the altitude ramp; helicopters are always amber. */
private fun targetColor(ac: Aircraft): Color =
    if (ac.category == "A7") Color(0xFFFFB300) else altitudeRamp(ac.altitudeFt)

@Composable
private fun SelectedBlock(ac: Aircraft, onClose: () -> Unit) {
    val p = FlightDeck
    var photo by remember(ac.hex) { mutableStateOf<PhotoClient.Photo?>(null) }
    LaunchedEffect(ac.hex) { photo = PhotoClient.fetch(ac.hex) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = FdDim.gutter, vertical = 6.dp)
            .clip(RoundedCornerShape(FdDim.radiusCard))
            .background(p.panel)
            .border(1.dp, p.stroke, RoundedCornerShape(FdDim.radiusCard))
            .padding(FdDim.cardPaddingTight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        photo?.let {
            AsyncImage(
                model = it.url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 70.dp, height = 55.dp)
                    .clip(RoundedCornerShape(FdDim.radiusChip)),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            PanelText(
                "${ac.callsign?.trim() ?: ac.registration ?: ac.hex} · ${friendlyType(ac.type) ?: "UNKNOWN"}".uppercase(),
                color = p.textHi,
                size = FdType.rowTitle,
                maxLines = 1,
            )
            PanelText(
                buildString {
                    append(ac.altitudeFt?.let { "$it FT" } ?: if (ac.onGround) "ON GROUND" else "— FT")
                    val rate = ac.verticalRateFpm ?: 0
                    append(
                        when {
                            rate > 250 -> " ↑"
                            rate < -250 -> " ↓"
                            else -> " ·"
                        },
                    )
                    append(" · ${ac.groundSpeedKt.roundToInt()} KT")
                    ac.squawk?.let { append(" · SQ $it") }
                },
                color = p.textDim,
                size = FdType.control,
                maxLines = 1,
            )
        }
        androidx.compose.material3.Text(
            "✕",
            modifier = Modifier
                .clickable(onClick = onClose)
                .padding(8.dp),
            fontFamily = B612Mono,
            fontSize = FdType.rowTitle,
            color = p.textFaint,
        )
    }
}

/** The AI decode strip: one line of transcript, typed in, behind a magenta rule. */
@Composable
private fun DecodeStrip(caption: String?, plainEnglish: Boolean) {
    val p = FlightDeck
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = FdDim.gutter, vertical = 6.dp)
            .clip(RoundedCornerShape(FdDim.radiusRow))
            .background(Color(0xE60F0814))
            .padding(vertical = 10.dp),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(42.dp)
                .background(p.magenta),
        )
        Column(Modifier.padding(start = 12.dp, end = 12.dp)) {
            SectionLabel(
                "AI DECODE · GROQ WHISPER" + if (plainEnglish) " · PLAIN ENGLISH" else "",
                color = p.magenta,
            )
            Spacer(Modifier.height(4.dp))
            if (caption.isNullOrBlank()) {
                PanelText("…", color = p.textGhost, size = FdType.control)
            } else {
                TypewriterText(caption, color = p.text, size = FdType.control)
            }
        }
    }
}

private fun latLngToOffset(
    center: LatLng,
    point: LatLng,
    cx: Float,
    cy: Float,
    scopeR: Float,
    rangeNm: Double,
): Offset {
    val north = (point.lat - center.lat) * 60.0
    val east = (point.lon - center.lon) * 60.0 * cos(Math.toRadians(center.lat))
    return Offset(cx + (east / rangeNm * scopeR).toFloat(), cy - (north / rangeNm * scopeR).toFloat())
}

/** Screen position for an aircraft, or null when it is outside the selected range. */
private fun aircraftOffset(
    center: LatLng,
    ac: Aircraft,
    cx: Float,
    cy: Float,
    scopeR: Float,
    rangeNm: Double,
): Offset? {
    val north = (ac.lat - center.lat) * 60.0
    val east = (ac.lon - center.lon) * 60.0 * cos(Math.toRadians(center.lat))
    if (hypot(north, east) > rangeNm) return null
    return Offset(cx + (east / rangeNm * scopeR).toFloat(), cy - (north / rangeNm * scopeR).toFloat())
}
