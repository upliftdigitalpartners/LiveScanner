package dev.fahim.livescanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import dev.fahim.livescanner.data.PhotoClient
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fahim.livescanner.data.AdsbClient
import dev.fahim.livescanner.data.Aircraft
import dev.fahim.livescanner.data.GroqTranscriber
import dev.fahim.livescanner.data.LatLng
import dev.fahim.livescanner.data.airlineName
import dev.fahim.livescanner.data.friendlyType
import dev.fahim.livescanner.data.verticalTrend
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SWEEP_PERIOD_SEC = 4.0
private const val POLL_MS = 5_000L
private const val MAX_LABELS = 55
private val RANGES = listOf(10.0, 20.0, 40.0)
private val SKINS = listOf(Color(0xFF38E08A), Color(0xFFFFC04D), Color(0xFF66D0FF)) // green / amber / blue
private val LOW_ALT = Color(0xFFFFB300)   // amber, near the ground
private val HIGH_ALT = Color(0xFF49D2FF)  // cyan, up high
private val LABEL_STYLE = TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace)

@Composable
fun RadarScreen(
    center: LatLng,
    title: String,
    streamUrl: String?,
    groqApiKey: String?,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    var rangeNm by remember { mutableStateOf(40.0) }
    var selectedHex by remember { mutableStateOf<String?>(null) }
    var aircraft by remember { mutableStateOf<List<Aircraft>>(emptyList()) }
    var captionsOn by remember { mutableStateOf(false) }
    var captions by remember { mutableStateOf<List<String>>(emptyList()) }
    var highlightedCallsigns by remember { mutableStateOf<Set<String>>(emptySet()) }
    var plainEnglish by remember { mutableStateOf(false) }
    var autoFollow by remember { mutableStateOf(true) }
    var skinIndex by remember { mutableStateOf(0) }
    val trails = remember { mutableStateMapOf<String, List<LatLng>>() }

    LaunchedEffect(center, rangeNm) {
        while (true) {
            val fresh = AdsbClient.fetchNear(center.lat, center.lon, rangeNm.toInt())
            aircraft = fresh
            for (ac in fresh) {
                trails[ac.hex] = (trails[ac.hex].orEmpty() + LatLng(ac.lat, ac.lon)).takeLast(8)
            }
            trails.keys.retainAll(fresh.mapTo(HashSet()) { it.hex })
            delay(POLL_MS)
        }
    }

    // Monotonic frame clock drives the sweep and per-aircraft dead-reckoning.
    var nowNanos by remember { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { nowNanos = it } }

    // Live transcription: capture a few seconds of the ATC audio and send it to Groq Whisper.
    LaunchedEffect(captionsOn, streamUrl, groqApiKey) {
        if (!captionsOn) return@LaunchedEffect
        if (streamUrl == null || groqApiKey == null) {
            captions = listOf("Add a Groq API key in Settings to enable live transcription.")
            return@LaunchedEffect
        }
        captions = listOf("Listening…")
        while (true) {
            val clip = GroqTranscriber.captureSegment(streamUrl, 8_000L)
            if (clip != null && clip.size > 800) {
                val text = GroqTranscriber.transcribe(clip, groqApiKey)
                if (!text.isNullOrBlank()) {
                    val line = text.trim()
                    val display = if (plainEnglish) GroqTranscriber.plainEnglish(line, groqApiKey) ?: line else line
                    captions = (captions.filterNot { it == "Listening…" } + display).takeLast(5)
                    val candidates = aircraft.mapNotNull { it.callsign }.distinct()
                    val hits = GroqTranscriber.identifyCallsigns(line, candidates, groqApiKey)
                    if (hits.isNotEmpty()) {
                        val hl = hits.map { it.trim().uppercase() }.toSet()
                        highlightedCallsigns = hl
                        if (autoFollow) {
                            aircraft.firstOrNull { it.callsign?.trim()?.uppercase() in hl }?.let { selectedHex = it.hex }
                        }
                    }
                }
            }
        }
    }

    val measurer = rememberTextMeasurer()
    val planeGlyph = remember { buildPlanePath() }
    val heliGlyph = remember { buildHeliPath() }
    val phosphor = SKINS[skinIndex]
    val inRange = aircraft.count { nmDistance(center, it) <= rangeNm }
    val selected = aircraft.firstOrNull { it.hex == selectedHex }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "$inRange contacts · ${rangeNm.toInt()} nm",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { captionsOn = !captionsOn }) {
                        Icon(
                            Icons.Default.Subtitles,
                            contentDescription = "Live captions",
                            tint = if (captionsOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { plainEnglish = !plainEnglish }) {
                        Icon(
                            Icons.Default.Translate,
                            contentDescription = "Plain English",
                            tint = if (plainEnglish) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { autoFollow = !autoFollow }) {
                        Icon(
                            Icons.Default.GpsFixed,
                            contentDescription = "Auto-follow",
                            tint = if (autoFollow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { skinIndex = (skinIndex + 1) % SKINS.size }) {
                        Icon(Icons.Default.Palette, contentDescription = "Scope color", tint = phosphor)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(aircraft, rangeNm) {
                        detectTapGestures { tap ->
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val scopeR = min(size.width, size.height) / 2f * 0.92f
                            val now = System.nanoTime()
                            var best: Aircraft? = null
                            var bestD = 38f
                            for (ac in aircraft) {
                                val p = aircraftOffset(center, ac, now, cx, cy, scopeR, rangeNm) ?: continue
                                val d = hypot(p.x - tap.x, p.y - tap.y)
                                if (d < bestD) {
                                    bestD = d
                                    best = ac
                                }
                            }
                            selectedHex = best?.hex
                        }
                    },
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val scopeR = min(size.width, size.height) / 2f * 0.92f
                val sweepDeg = ((nowNanos / 1e9) / SWEEP_PERIOD_SEC * 360.0 % 360.0).toFloat()
                val edge = { bearing: Double, len: Float ->
                    Offset(
                        cx + (sin(Math.toRadians(bearing)) * len).toFloat(),
                        cy - (cos(Math.toRadians(bearing)) * len).toFloat(),
                    )
                }

                // Soft phosphor glow at the field.
                drawCircle(
                    Brush.radialGradient(listOf(phosphor.copy(alpha = 0.10f), Color.Transparent), Offset(cx, cy), scopeR),
                    radius = scopeR,
                    center = Offset(cx, cy),
                )

                // Range rings + labels.
                for (ring in 1..4) {
                    val r = scopeR * ring / 4f
                    drawCircle(phosphor.copy(alpha = 0.22f), r, Offset(cx, cy), style = Stroke(1.5f))
                    drawText(
                        measurer, "${(rangeNm / 4 * ring).toInt()}",
                        topLeft = Offset(cx + 4f, cy - r),
                        style = TextStyle(color = phosphor.copy(alpha = 0.35f), fontSize = 8.sp, fontFamily = FontFamily.Monospace),
                    )
                }

                // Crosshair + cardinals.
                drawLine(phosphor.copy(alpha = 0.15f), Offset(cx, cy - scopeR), Offset(cx, cy + scopeR))
                drawLine(phosphor.copy(alpha = 0.15f), Offset(cx - scopeR, cy), Offset(cx + scopeR, cy))
                val cardinalStyle = TextStyle(color = phosphor.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                drawText(measurer, "N", topLeft = Offset(cx - 4f, cy - scopeR - 16f), style = cardinalStyle)
                drawText(measurer, "S", topLeft = Offset(cx - 4f, cy + scopeR + 2f), style = cardinalStyle)
                drawText(measurer, "E", topLeft = Offset(cx + scopeR + 4f, cy - 8f), style = cardinalStyle)
                drawText(measurer, "W", topLeft = Offset(cx - scopeR - 16f, cy - 8f), style = cardinalStyle)

                // Sweep (with a soft leading glow).
                drawLine(phosphor.copy(alpha = 0.10f), Offset(cx, cy), edge(sweepDeg.toDouble(), scopeR), strokeWidth = 16f)
                for (i in 0 until 28) {
                    val a = sweepDeg - i * 2.2f
                    val alpha = (1f - i / 28f) * 0.5f
                    drawLine(phosphor.copy(alpha = alpha), Offset(cx, cy), edge(a.toDouble(), scopeR), strokeWidth = if (i == 0) 2.5f else 1.5f)
                }

                val visible = aircraft.mapNotNull { ac ->
                    aircraftOffset(center, ac, nowNanos, cx, cy, scopeR, rangeNm)?.let { ac to it }
                }

                // Fading position trails (older = dimmer).
                for ((ac, _) in visible) {
                    val tr = trails[ac.hex] ?: continue
                    if (tr.size < 2) continue
                    val tcol = altitudeColor(ac.altitudeFt)
                    for (i in 1 until tr.size) {
                        val a = latLngToOffset(center, tr[i - 1].lat, tr[i - 1].lon, cx, cy, scopeR, rangeNm)
                        val b = latLngToOffset(center, tr[i].lat, tr[i].lon, cx, cy, scopeR, rangeNm)
                        drawLine(tcol.copy(alpha = 0.22f * i / tr.size), a, b, strokeWidth = 1.5f)
                    }
                }

                // Glyphs + leader lines for every contact.
                for ((ac, pos) in visible) {
                    val base = altitudeColor(ac.altitudeFt)
                    if (ac.groundSpeedKt > 5) {
                        val leadPx = (ac.groundSpeedKt / 60.0 / rangeNm * scopeR).toFloat()
                        val tr = Math.toRadians(ac.trackDeg)
                        drawLine(
                            base.copy(alpha = 0.3f), pos,
                            Offset(pos.x + (sin(tr) * leadPx).toFloat(), pos.y - (cos(tr) * leadPx).toFloat()),
                            strokeWidth = 1.5f,
                        )
                    }
                    val bearing = (Math.toDegrees(atan2((pos.x - cx).toDouble(), (cy - pos.y).toDouble())) + 360.0) % 360.0
                    val since = (((sweepDeg - bearing) % 360.0) + 360.0) % 360.0 / 360.0
                    val flare = (0.5 + 0.5 * (1.0 - since)).toFloat()
                    val cat = ac.category
                    val glyphPath = if (cat == "A7") heliGlyph else planeGlyph
                    val glyphScale = when (cat) {
                        "A5" -> 1.35f       // heavy
                        "A1", "A2" -> 0.8f  // light / small
                        else -> 1f
                    }
                    withTransform({
                        translate(pos.x, pos.y)
                        rotate(ac.trackDeg.toFloat(), pivot = Offset.Zero)
                        scale(glyphScale, glyphScale, pivot = Offset.Zero)
                    }) {
                        drawPath(glyphPath, color = if (ac.emergency) Color(0xFFFF5252) else base.copy(alpha = flare))
                    }
                }

                // Labels: nearest-to-field first, skip any that would overlap one already drawn.
                val placed = ArrayList<Rect>(MAX_LABELS)
                val ordered = visible.sortedBy {
                    val dx = it.second.x - cx; val dy = it.second.y - cy; dx * dx + dy * dy
                }
                for ((ac, pos) in ordered) {
                    if (placed.size >= MAX_LABELS) break
                    if (ac.hex == selectedHex) continue
                    val cs = ac.callsign ?: continue
                    val layout = measurer.measure(cs, LABEL_STYLE)
                    val left = pos.x + 8f
                    val top = pos.y - 6f
                    val rect = Rect(left, top, left + layout.size.width, top + layout.size.height).inflate(2f)
                    if (placed.any { it.overlaps(rect) }) continue
                    placed.add(rect)
                    drawText(layout, color = altitudeColor(ac.altitudeFt).copy(alpha = 0.9f), topLeft = Offset(left, top))
                }

                // Selected aircraft: pulsing ring + bold label, always on top.
                if (selected != null) {
                    val sp = aircraftOffset(center, selected, nowNanos, cx, cy, scopeR, rangeNm)
                    if (sp != null) {
                        val pulse = 11f + 3f * sin(nowNanos / 1e9 * 4.0).toFloat()
                        drawCircle(Color.White, radius = pulse, center = sp, style = Stroke(2f))
                        selected.callsign?.let {
                            val l = measurer.measure(it, LABEL_STYLE.copy(fontWeight = FontWeight.Bold))
                            drawText(l, color = Color.White, topLeft = Offset(sp.x + 12f, sp.y - 6f))
                        }
                    }
                }

                // "Being talked to" (from the AI captions): gold halo + pulse on that aircraft.
                if (highlightedCallsigns.isNotEmpty()) {
                    val gold = Color(0xFFFFC400)
                    for (ac in aircraft) {
                        val cs = ac.callsign?.trim() ?: continue
                        if (cs.uppercase() !in highlightedCallsigns) continue
                        val hp = aircraftOffset(center, ac, nowNanos, cx, cy, scopeR, rangeNm) ?: continue
                        val pulse = 13f + 4f * sin(nowNanos / 1e9 * 5.0).toFloat()
                        drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = 0.4f), Color.Transparent), hp, 26f), radius = 26f, center = hp)
                        drawCircle(gold, radius = pulse, center = hp, style = Stroke(2.5f))
                        val l = measurer.measure(cs, LABEL_STYLE.copy(fontWeight = FontWeight.Bold))
                        drawText(l, color = gold, topLeft = Offset(hp.x + 13f, hp.y - 6f))
                    }
                }

                // The field at center.
                drawCircle(phosphor, 4f, Offset(cx, cy))
                drawCircle(phosphor.copy(alpha = 0.5f), 8f, Offset(cx, cy), style = Stroke(1.5f))

                // CRT vignette.
                drawRect(
                    Brush.radialGradient(
                        0f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1f to Color(0x9905080B),
                        center = Offset(cx, cy),
                        radius = hypot(size.width.toDouble(), size.height.toDouble()).toFloat() / 2f,
                    ),
                )
            }

            // Range selector.
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RANGES.forEach { r ->
                    FilterChip(
                        selected = rangeNm == r,
                        onClick = { rangeNm = r; selectedHex = null },
                        label = { Text("${r.toInt()} nm") },
                    )
                }
            }

            // Bottom overlays: live captions and/or the tapped-aircraft detail card.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (captionsOn) {
                    CaptionsPanel(lines = captions, modifier = Modifier.fillMaxWidth())
                }
                if (selected != null) {
                    AircraftCard(
                        ac = selected,
                        onClose = { selectedHex = null },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AircraftCard(ac: Aircraft, onClose: () -> Unit, modifier: Modifier) {
    var photo by remember(ac.hex) { mutableStateOf<PhotoClient.Photo?>(null) }
    LaunchedEffect(ac.hex) { photo = PhotoClient.fetch(ac.hex) }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            photo?.let { p ->
                AsyncImage(
                    model = p.url,
                    contentDescription = "Aircraft photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                val airline = airlineName(ac.callsign)
                val number = ac.callsign?.trim()?.dropWhile { it.isLetter() }?.trim().orEmpty()
                val titleText = if (airline != null && number.isNotEmpty()) {
                    "$airline $number"
                } else {
                    ac.callsign ?: ac.registration ?: ac.hex
                }
                Text(titleText, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        friendlyType(ac.type)?.let { append(it); append(" · ") }
                        append(ac.altitudeFt?.let { "$it ft" } ?: if (ac.onGround) "on ground" else "—")
                        verticalTrend(ac.verticalRateFpm)?.let { if (it != "level") append(" ($it)") }
                        append(" · ")
                        append("${ac.groundSpeedKt.roundToInt()} kt")
                        ac.squawk?.let { append(" · sq "); append(it) }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                photo?.credit?.let {
                    Text(
                        "📷 $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }
    }
}

@Composable
private fun CaptionsPanel(lines: List<String>, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0xCC0B1116),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (lines.isEmpty()) {
                Text("…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                lines.forEachIndexed { index, line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (index == lines.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A small top-down jet silhouette around the origin, nose pointing up (north). */
private fun buildPlanePath(): Path = Path().apply {
    moveTo(0f, -9f)
    lineTo(1.6f, -4f)
    lineTo(1.6f, -0.5f)
    lineTo(9f, 2.5f)
    lineTo(9f, 4.2f)
    lineTo(1.6f, 2.5f)
    lineTo(1.6f, 6f)
    lineTo(3.8f, 8f)
    lineTo(3.8f, 9.2f)
    lineTo(0f, 7.6f)
    lineTo(-3.8f, 9.2f)
    lineTo(-3.8f, 8f)
    lineTo(-1.6f, 6f)
    lineTo(-1.6f, 2.5f)
    lineTo(-9f, 4.2f)
    lineTo(-9f, 2.5f)
    lineTo(-1.6f, -0.5f)
    lineTo(-1.6f, -4f)
    close()
}

/** A stylized rotor-cross marker for helicopters. */
private fun buildHeliPath(): Path = Path().apply {
    addRect(Rect(-9f, -1.3f, 9f, 1.3f))
    addRect(Rect(-1.3f, -9f, 1.3f, 9f))
    addOval(Rect(-3.2f, -3.2f, 3.2f, 3.2f))
}

private fun latLngToOffset(
    center: LatLng,
    lat: Double,
    lon: Double,
    cx: Float,
    cy: Float,
    scopeR: Float,
    rangeNm: Double,
): Offset {
    val north = (lat - center.lat) * 60.0
    val east = (lon - center.lon) * 60.0 * cos(Math.toRadians(center.lat))
    return Offset(cx + (east / rangeNm * scopeR).toFloat(), cy - (north / rangeNm * scopeR).toFloat())
}

private fun altitudeColor(ft: Int?): Color {
    if (ft == null) return Color(0xFF9AA0A6) // on ground / unknown
    val t = ft.coerceIn(0, 40000) / 40000f
    return lerp(LOW_ALT, HIGH_ALT, t)
}

private fun nmDistance(center: LatLng, ac: Aircraft): Double {
    val north = (ac.lat - center.lat) * 60.0
    val east = (ac.lon - center.lon) * 60.0 * cos(Math.toRadians(center.lat))
    return hypot(north, east)
}

/** Screen position for an aircraft, dead-reckoned forward from its last fix; null if beyond range. */
private fun aircraftOffset(
    center: LatLng,
    ac: Aircraft,
    nowNanos: Long,
    cx: Float,
    cy: Float,
    scopeR: Float,
    rangeNm: Double,
): Offset? {
    var north = (ac.lat - center.lat) * 60.0
    var east = (ac.lon - center.lon) * 60.0 * cos(Math.toRadians(center.lat))
    if (ac.groundSpeedKt > 0 && nowNanos > ac.seenNanos) {
        val dt = (nowNanos - ac.seenNanos) / 1e9
        val dnm = ac.groundSpeedKt / 3600.0 * dt
        north += dnm * cos(Math.toRadians(ac.trackDeg))
        east += dnm * sin(Math.toRadians(ac.trackDeg))
    }
    if (hypot(north, east) > rangeNm) return null
    return Offset(
        cx + (east / rangeNm * scopeR).toFloat(),
        cy - (north / rangeNm * scopeR).toFloat(),
    )
}
