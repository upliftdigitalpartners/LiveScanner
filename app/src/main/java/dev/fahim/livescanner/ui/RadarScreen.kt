package dev.fahim.livescanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.fahim.livescanner.data.Aircraft
import dev.fahim.livescanner.data.LatLng
import dev.fahim.livescanner.data.PhotoClient
import dev.fahim.livescanner.data.RegistryClient
import dev.fahim.livescanner.data.friendlyType
import dev.fahim.livescanner.ui.theme.B612Mono
import dev.fahim.livescanner.ui.theme.FdDim
import dev.fahim.livescanner.ui.theme.FdTracking
import dev.fahim.livescanner.ui.theme.FdType
import dev.fahim.livescanner.ui.theme.FlightDeck
import dev.fahim.livescanner.ui.theme.altitudeRamp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val SWEEP_PERIOD_SEC = 4.0
private const val MAX_EXTRAPOLATION_SEC = 15.0
private const val RIPPLE_SEC = 1.6
private val RANGE_PRESETS = listOf(10f, 20f, 40f)

/** Text laid out once per ADS-B update rather than once per frame. */
private class TargetLabels(val callsign: TextLayoutResult?, val tag: TextLayoutResult?)

/** North/east offset in nautical miles from the scope origin. */
private class NorthEast(val north: Double, val east: Double)

/**
 * The navigation display: a scope centred on the airport you are listening to, the traffic ADS-B
 * can see, and the AI decode of what the tower just said.
 *
 * Every animation is gated on playback — a scope that keeps sweeping while the audio is paused is
 * lying about being live.
 */
@Composable
fun RadarScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = FlightDeck
    val radar by vm.radar.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()

    val feed = playback.feed
    val field = feed?.let { f ->
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
            subtitle = "${radar.aircraft.size} CONTACTS · RNG ${radar.rangeNm.roundToInt()}",
            onBack = onBack,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FdKey("CC", radar.captionsOn, FdAccent.CYAN) { vm.toggleCaptions() }
                    FdKey("EN", radar.plainEnglishOn, FdAccent.MAGENTA) { vm.togglePlainEnglish() }
                    FdKey("FLW", radar.followOn, FdAccent.GREEN) { vm.toggleFollow() }
                    FdKey("WX", radar.weatherOn, FdAccent.CYAN) { vm.toggleWeather() }
                }
            },
        )

        HeadingTape(rotationDeg = radar.rotationDeg, trackUp = radar.trackUp)

        if (field == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                PanelText("NO SCOPE — TUNE AN AIRPORT FEED", color = p.textFaint, size = FdType.control)
            }
        } else {
            val origin = radar.centerFor(field)
            val icao = feed.displayCode
            // Loaded once per airport and cached by the loader; inland fields simply get none.
            val shoreline = remember(icao) { vm.coastline.forAirport(icao) }
            val weather = rememberWeatherTiles(origin, radar.rangeNm, radar.weatherOn)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Scope(
                    origin = origin,
                    radar = radar,
                    shoreline = shoreline,
                    weather = weather,
                    playing = playback.isPlaying,
                    onSelect = vm::selectAircraft,
                    onTrack = vm::trackAircraft,
                    onZoom = vm::zoomRange,
                    onPan = vm::panScope,
                )
                CornerReadouts(
                    approachBearing = radar.approachBearing,
                    modifier = Modifier.align(Alignment.TopStart).padding(FdDim.gutter),
                )
                // Offset scope: offer the way back rather than stranding the user off-field.
                if (radar.centerOffsetNm != Offset.Zero) {
                    Box(Modifier.align(Alignment.TopEnd).padding(FdDim.gutter)) {
                        FdChip("↺ RECENTER", FdAccent.AMBER, onClick = vm::recenterScope)
                    }
                }
            }

            SideProfile(
                aircraft = radar.aircraft,
                centerLat = origin.lat,
                centerLon = origin.lon,
                rangeNm = radar.rangeNm,
                selectedHex = radar.selectedHex,
                modifier = Modifier.padding(horizontal = FdDim.gutter, vertical = 4.dp),
            )
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("RNG")
            RANGE_PRESETS.forEach { r ->
                FdKey(
                    r.roundToInt().toString(),
                    active = abs(radar.rangeNm - r) < 0.5f,
                    accent = FdAccent.CYAN,
                    modifier = Modifier.weight(1f),
                ) { vm.setRange(r) }
            }
            FdKey("TRK↑", radar.trackUp, FdAccent.GREEN) { vm.toggleTrackUp() }
        }
    }
}

/** Compass tape across the top. In track-up it slides so the approach heading sits on the index. */
@Composable
private fun HeadingTape(rotationDeg: Float, trackUp: Boolean) {
    val p = FlightDeck
    // The tape shows the heading now at the top of the scope, which is -rotation.
    val topHeading = ((-rotationDeg % 360f) + 360f) % 360f
    val ticks = remember(topHeading) {
        (-4..4).map { step ->
            val deg = ((topHeading + step * 10f) % 360f + 360f) % 360f
            val label = (deg / 10f).roundToInt() % 36
            if (label == 0) "36" else label.toString().padStart(2, '0')
        }
    }
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
            ticks.forEachIndexed { index, tick ->
                val centre = index == ticks.size / 2
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
            if (trackUp) "APP ${topHeading.roundToInt().toString().padStart(3, '0')}" else "HDG 360",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp),
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
private fun CornerReadouts(approachBearing: Float?, modifier: Modifier) {
    val p = FlightDeck
    Row(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            SectionLabel("GS 000", color = p.green)
            SectionLabel("WX VFR", color = p.green)
            approachBearing?.let {
                SectionLabel("APP ${it.roundToInt().toString().padStart(3, '0')}", color = p.green)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            SectionLabel("ADSB LIVE", color = p.cyan)
            SectionLabel("5.0S POLL", color = p.cyan)
        }
    }
}

@Composable
private fun Scope(
    origin: LatLng,
    radar: RadarUiState,
    shoreline: List<List<LatLng>>,
    weather: List<WeatherTile>,
    playing: Boolean,
    onSelect: (String?) -> Unit,
    onTrack: (String) -> Unit,
    onZoom: (Float) -> Unit,
    onPan: (Float, Float) -> Unit,
) {
    val p = FlightDeck
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val rangeNm = radar.rangeNm.toDouble()
    val rotation = radar.rotationDeg

    // One monotonic clock drives the sweep, the pulses and the dead-reckoning; it stops with audio.
    var nowNanos by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) withFrameNanos { nowNanos = it }
    }

    // When the ripple target changes, note the moment so the animation has a start.
    var rippleStart by remember { mutableLongStateOf(0L) }
    LaunchedEffect(radar.rippleHex) { rippleStart = System.nanoTime() }

    val tagStyle = remember(p.textDim) {
        TextStyle(fontFamily = B612Mono, fontSize = FdType.dataTag, color = p.textDim)
    }

    // Everything below is measured or allocated once per data change, never per frame. Laying out
    // two text labels per contact inside the draw loop was the most expensive thing the scope did.
    val labels = remember(radar.aircraft, tagStyle) {
        radar.aircraft.associate { ac ->
            val callsign = ac.callsign?.trim()?.takeIf { it.isNotEmpty() }
            val fl = ac.altitudeFt?.let { (it / 100).toString().padStart(3, '0') } ?: "GND"
            val rate = ac.verticalRateFpm ?: 0
            val trend = when {
                rate > 250 -> "↑"
                rate < -250 -> "↓"
                else -> "·"
            }
            ac.hex to TargetLabels(
                callsign = callsign?.let { measurer.measure(it, tagStyle) },
                tag = measurer.measure("$fl$trend ${(ac.groundSpeedKt / 10).roundToInt()}", tagStyle),
            )
        }
    }
    val rangeLabels = remember(radar.rangeNm, tagStyle) {
        listOf(1.0f, 0.75f, 0.5f).map { frac ->
            frac to measurer.measure((radar.rangeNm * frac).roundToInt().toString(), tagStyle)
        }
    }
    val trkLabel = remember(tagStyle) { measurer.measure("TRK", tagStyle) }

    val sweepBrush = remember(p.green) {
        Brush.sweepGradient(
            0f to p.green.copy(alpha = 0.28f),
            (80f / 360f) to Color.Transparent,
            1f to Color.Transparent,
        )
    }
    val diamond = remember { Path() }
    val scopeClip = remember { Path() }
    val dashed = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 8f)) }

    // Smoothed screen offsets per contact. Dead reckoning is continuous, but a new fix corrects the
    // estimate in one step; easing onto it turns that correction into a settle rather than a jump.
    val smoothed = remember { HashMap<String, NorthEast>() }

    Canvas(
        Modifier
            .fillMaxSize()
            // Tap selects, long-press arms a tracking rule for that contact.
            .pointerInput(radar.aircraft, rangeNm, rotation) {
                val pick = { at: Offset ->
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val scopeR = min(size.width, size.height) / 2f * 0.92f
                    val nanos = System.nanoTime()
                    var best: Aircraft? = null
                    var bestD = with(density) { 30.dp.toPx() }
                    for (ac in radar.aircraft) {
                        val pos = projectAircraft(origin, ac, nanos, rotation, cx, cy, scopeR, rangeNm)
                            ?: continue
                        val d = hypot(pos.x - at.x, pos.y - at.y)
                        if (d < bestD) {
                            bestD = d
                            best = ac
                        }
                    }
                    best
                }
                detectTapGestures(
                    onTap = { onSelect(pick(it)?.hex) },
                    onLongPress = { pick(it)?.let { ac -> onTrack(ac.hex) } },
                )
            }
            // Pinch zooms the range continuously; drag moves the scope origin off the field.
            .pointerInput(rangeNm, rotation) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val scopeR = min(size.width, size.height) / 2f * 0.92f
                    if (zoom != 1f) onZoom(zoom)
                    if (pan != Offset.Zero) {
                        val nmPerPx = (rangeNm / scopeR).toFloat()
                        // Screen y is inverted, and a rotated picture needs the drag un-rotated.
                        val (east, north) = unrotate(-pan.x * nmPerPx, pan.y * nmPerPx, rotation)
                        onPan(east, north)
                    }
                }
            },
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val scopeR = min(size.width, size.height) / 2f * 0.92f
        val seconds = nowNanos / 1e9

        // Precipitation radar sits below every other layer: it is context, not data. Corners are
        // projected unrotated and the whole layer is turned as one, which is both cheaper than
        // rotating each tile and the only way a bitmap can follow a rotated picture.
        if (weather.isNotEmpty()) {
            scopeClip.rewind()
            scopeClip.addOval(Rect(cx - scopeR, cy - scopeR, cx + scopeR, cy + scopeR))
            clipPath(scopeClip) {
                rotate(degrees = rotation, pivot = Offset(cx, cy)) {
                    for (cell in weather) {
                        val nw = projectLatLng(
                            origin, LatLng(cell.tile.northLat, cell.tile.westLon),
                            0f, cx, cy, scopeR, rangeNm,
                        )
                        val se = projectLatLng(
                            origin, LatLng(cell.tile.southLat, cell.tile.eastLon),
                            0f, cx, cy, scopeR, rangeNm,
                        )
                        val w = (se.x - nw.x).roundToInt()
                        val h = (se.y - nw.y).roundToInt()
                        if (w <= 0 || h <= 0) continue
                        drawImage(
                            image = cell.image,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(cell.image.width, cell.image.height),
                            dstOffset = IntOffset(nw.x.roundToInt(), nw.y.roundToInt()),
                            dstSize = IntSize(w, h),
                            alpha = 0.40f,
                        )
                    }
                }
            }
        }

        // Shoreline underneath everything: the strongest orienting cue a coastal field has. Drawn
        // very faint so it reads as ground truth rather than as data.
        if (shoreline.isNotEmpty()) {
            val shore = p.cyan.copy(alpha = 0.16f)
            for (line in shoreline) {
                var previous: Offset? = null
                for (point in line) {
                    val here = projectLatLng(origin, point, rotation, cx, cy, scopeR, rangeNm)
                    val prev = previous
                    // Only draw a segment when at least one end is on the scope.
                    if (prev != null &&
                        (hypot(prev.x - cx, prev.y - cy) < scopeR || hypot(here.x - cx, here.y - cy) < scopeR)
                    ) {
                        drawLine(shore, prev, here, strokeWidth = 1f)
                    }
                    previous = here
                }
            }
        }

        // Rings: alternating solid and dashed, cyan at 10-25% as the spec's rgba values.
        for (ring in 1..4) {
            val r = scopeR * ring / 4f
            val solid = ring % 2 == 1
            drawCircle(
                color = p.cyan.copy(alpha = if (solid) 0.25f else 0.10f),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx(), pathEffect = if (solid) null else dashed),
            )
        }

        drawLine(p.cyan.copy(alpha = 0.12f), Offset(cx, cy - scopeR), Offset(cx, cy + scopeR))
        drawLine(p.cyan.copy(alpha = 0.12f), Offset(cx - scopeR, cy), Offset(cx + scopeR, cy))

        val rangeTint = p.cyan.copy(alpha = 0.45f)
        rangeLabels.forEach { (frac, layout) ->
            drawText(layout, color = rangeTint, topLeft = Offset(cx + 6f, cy - scopeR * frac))
        }

        // Approach corridor: the direction arrivals are actually coming from, inferred from the
        // traffic itself rather than from runway data the app doesn't carry.
        radar.approachBearing?.let { bearing ->
            val fromBearing = (bearing + 180f).toDouble()
            val screenDeg = fromBearing + rotation
            val rad = Math.toRadians(screenDeg)
            val tip = Offset(
                cx + (sin(rad) * scopeR).toFloat(),
                cy - (cos(rad) * scopeR).toFloat(),
            )
            drawLine(
                p.green.copy(alpha = 0.30f),
                Offset(cx, cy),
                tip,
                strokeWidth = 1.dp.toPx(),
                pathEffect = dashed,
            )
            for (spread in listOf(-3.0, 3.0)) {
                val edgeRad = Math.toRadians(screenDeg + spread)
                drawLine(
                    p.green.copy(alpha = 0.14f),
                    Offset(cx, cy),
                    Offset(
                        cx + (sin(edgeRad) * scopeR).toFloat(),
                        cy - (cos(edgeRad) * scopeR).toFloat(),
                    ),
                    strokeWidth = 1f,
                )
            }
        }

        // Sweep: a conic wedge, one rotation per 4 s, frozen when the audio is paused.
        val sweepDeg = if (playing) ((seconds / SWEEP_PERIOD_SEC) * 360.0 % 360.0).toFloat() else 0f
        rotate(degrees = sweepDeg, pivot = Offset(cx, cy)) {
            drawCircle(brush = sweepBrush, radius = scopeR, center = Offset(cx, cy))
        }

        // Ownship: a square outline at the field, which is not the scope centre once panned.
        val fieldPos = projectNorthEast(
            NorthEast(-radar.centerOffsetNm.y.toDouble(), -radar.centerOffsetNm.x.toDouble()),
            rotation, cx, cy, scopeR, rangeNm,
        )
        val own = 13.dp.toPx() / 2f
        drawRect(
            color = p.green,
            topLeft = Offset(fieldPos.x - own, fieldPos.y - own),
            size = Size(own * 2, own * 2),
            style = Stroke(width = 1.5.dp.toPx()),
        )

        // Resolve every contact once, easing onto the dead-reckoned estimate.
        val visible = ArrayList<Pair<Aircraft, Offset>>(radar.aircraft.size)
        for (ac in radar.aircraft) {
            val target = deadReckon(origin, ac, nowNanos) ?: continue
            val previous = smoothed[ac.hex]
            val eased = if (previous == null) {
                target
            } else {
                NorthEast(
                    previous.north + (target.north - previous.north) * SMOOTHING,
                    previous.east + (target.east - previous.east) * SMOOTHING,
                )
            }
            smoothed[ac.hex] = eased
            if (hypot(eased.north, eased.east) > rangeNm) continue
            visible.add(ac to projectNorthEast(eased, rotation, cx, cy, scopeR, rangeNm))
        }
        smoothed.keys.retainAll(radar.aircraft.mapTo(HashSet()) { it.hex })

        // Fading position trails, oldest faintest.
        for ((ac, _) in visible) {
            val trail = radar.trails[ac.hex] ?: continue
            if (trail.size < 2) continue
            val tint = targetColor(ac)
            for (i in 1 until trail.size) {
                val a = projectLatLng(origin, trail[i - 1], rotation, cx, cy, scopeR, rangeNm)
                val b = projectLatLng(origin, trail[i], rotation, cx, cy, scopeR, rangeNm)
                drawLine(tint.copy(alpha = 0.22f * i / trail.size), a, b, strokeWidth = 1.5f)
            }
        }

        val maxPost = 26.dp.toPx()
        for ((ac, pos) in visible) {
            val base = targetColor(ac)

            // Phosphor persistence: a contact flares as the sweep crosses it and decays behind it.
            val bearing = (Math.toDegrees(
                kotlin.math.atan2((pos.x - cx).toDouble(), (cy - pos.y).toDouble()),
            ) + 360.0) % 360.0
            val since = (((sweepDeg - bearing) % 360.0) + 360.0) % 360.0 / 360.0
            val flare = if (playing) (0.45 + 0.55 * (1.0 - since)).toFloat() else 1f
            val tint = base.copy(alpha = (base.alpha * flare).coerceIn(0f, 1f))

            // Altitude post: a stalk toward the ground plane, giving the plan view some depth.
            ac.altitudeFt?.let { alt ->
                val h = sqrt((alt.coerceIn(0, 40_000) / 40_000f)) * maxPost
                drawLine(
                    base.copy(alpha = 0.28f * flare),
                    pos,
                    Offset(pos.x, pos.y + h),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    base.copy(alpha = 0.45f * flare),
                    Offset(pos.x - 2.dp.toPx(), pos.y + h),
                    Offset(pos.x + 2.dp.toPx(), pos.y + h),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // Lead vector: groundspeed/12 px, along the track, rotated with the picture.
            if (ac.groundSpeedKt > 5) {
                val len = (ac.groundSpeedKt / 12.0).toFloat() * (density.density / 2f)
                val rad = Math.toRadians(ac.trackDeg + rotation)
                drawLine(
                    tint.copy(alpha = 0.45f),
                    pos,
                    Offset(pos.x + (sin(rad) * len).toFloat(), pos.y - (cos(rad) * len).toFloat()),
                    strokeWidth = 1.5f,
                )
            }

            val half = 12.dp.toPx() / 2f
            diamond.rewind()
            diamond.moveTo(pos.x, pos.y - half)
            diamond.lineTo(pos.x + half, pos.y)
            diamond.lineTo(pos.x, pos.y + half)
            diamond.lineTo(pos.x - half, pos.y)
            diamond.close()
            drawPath(diamond, color = tint)

            val callsign = ac.callsign?.trim()
            val label = labels[ac.hex]
            label?.callsign?.let {
                drawText(it, color = tint, topLeft = Offset(pos.x + half + 4f, pos.y - half - 2f))
            }
            label?.tag?.let {
                drawText(
                    it,
                    color = tint.copy(alpha = 0.7f * flare),
                    topLeft = Offset(pos.x + half + 4f, pos.y + 1f),
                )
            }

            if (ac.hex == radar.selectedHex) {
                val pulse = if (playing) {
                    1f + 0.15f * sin(seconds * 2.0 * Math.PI / 1.3).toFloat()
                } else {
                    1f
                }
                drawCircle(Color.White, half * 1.9f * pulse, pos, style = Stroke(2.dp.toPx()))
            }

            if (callsign != null && callsign.uppercase() in radar.transcribing) {
                val pulse = if (playing) {
                    1f + 0.15f * sin(seconds * 2.0 * Math.PI / 1.2).toFloat()
                } else {
                    1f
                }
                drawCircle(p.magenta, half * 2.2f * pulse, pos, style = Stroke(2.dp.toPx()))
            }

            val isTracked = radar.tracked.any { wanted ->
                wanted.equals(callsign, ignoreCase = true) ||
                    wanted.equals(ac.registration?.trim(), ignoreCase = true)
            }
            if (isTracked) {
                drawCircle(p.amber, half * 2.6f, pos, style = Stroke(2.dp.toPx()))
                drawText(trkLabel, color = p.amber, topLeft = Offset(pos.x - half, pos.y + half * 2.8f))
            }

            // Expanding ripple from whoever was just spoken to — the audio, placed on the scope.
            if (ac.hex == radar.rippleHex) {
                val phase = (((nowNanos - rippleStart) / 1e9) / RIPPLE_SEC).coerceIn(0.0, 1.0).toFloat()
                if (phase < 1f) {
                    drawCircle(
                        p.magenta.copy(alpha = (1f - phase) * 0.7f),
                        half * (1.5f + phase * 6f),
                        pos,
                        style = Stroke(2.dp.toPx() * (1f - phase * 0.6f)),
                    )
                }
            }

            // Green ping on a contact that just entered range.
            if (ac.hex == radar.newHex) {
                val phase = ((seconds % 1.1) / 1.1).toFloat()
                drawCircle(
                    p.green.copy(alpha = 1f - phase),
                    half * (0.2f + phase * 2.2f),
                    pos,
                    style = Stroke(1.5.dp.toPx()),
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
    var registry by remember(ac.hex) { mutableStateOf<RegistryClient.Registration?>(null) }
    LaunchedEffect(ac.hex) { photo = PhotoClient.fetch(ac.hex) }
    // The FAA registry knows every US civil aircraft; the built-in type table knows a few dozen.
    LaunchedEffect(ac.hex) { registry = RegistryClient.lookup(ac.hex) }

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
            val ident = ac.callsign?.trim()
                ?: registry?.nNumber
                ?: ac.registration
                ?: ac.hex
            val typeLabel = registry?.typeLabel
                ?: friendlyType(ac.type)
                ?: ac.type
                ?: "UNKNOWN"
            PanelText(
                "$ident · $typeLabel".uppercase(),
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
            // Registry extras, only when the lookup actually returned something.
            registry?.let { reg ->
                val detail = listOfNotNull(
                    reg.nNumber?.takeIf { ac.callsign != null },
                    reg.year,
                    reg.owner,
                ).joinToString(" · ")
                if (detail.isNotBlank()) {
                    PanelText(
                        detail.uppercase(),
                        color = p.textFaint,
                        size = FdType.control,
                        maxLines = 1,
                    )
                }
            }
        }
        androidx.compose.material3.Text(
            "✕",
            modifier = Modifier.clickable(onClick = onClose).padding(8.dp),
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
        Box(Modifier.width(4.dp).height(42.dp).background(p.magenta))
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

private const val SMOOTHING = 0.18

/**
 * Rotates a screen-space drag back into world axes, so panning follows the finger even when the
 * picture is turned to track-up.
 */
private fun unrotate(east: Float, north: Float, rotationDeg: Float): Pair<Float, Float> {
    val rad = Math.toRadians(-rotationDeg.toDouble())
    val e = east * cos(rad) + north * sin(rad)
    val n = north * cos(rad) - east * sin(rad)
    return e.toFloat() to n.toFloat()
}

/**
 * Where an aircraft is *now*, dead-reckoned forward from its last fix.
 *
 * ADS-B arrives every 5 seconds. Plotting those fixes raw makes traffic jump, so each target is
 * carried forward along its own track at its own groundspeed. A new fix corrects the estimate.
 */
private fun deadReckon(origin: LatLng, ac: Aircraft, nowNanos: Long): NorthEast? {
    var north = (ac.lat - origin.lat) * 60.0
    var east = (ac.lon - origin.lon) * 60.0 * cos(Math.toRadians(origin.lat))
    if (ac.groundSpeedKt > 0 && nowNanos > ac.seenNanos) {
        // Clamp so a fix that stopped updating can't send a target flying off the scope.
        val dt = ((nowNanos - ac.seenNanos) / 1e9).coerceAtMost(MAX_EXTRAPOLATION_SEC)
        val dnm = ac.groundSpeedKt / 3600.0 * dt
        north += dnm * cos(Math.toRadians(ac.trackDeg))
        east += dnm * sin(Math.toRadians(ac.trackDeg))
    }
    return NorthEast(north, east)
}

/** Projects a world offset to the screen, applying the scope's rotation. */
private fun projectNorthEast(
    at: NorthEast,
    rotationDeg: Float,
    cx: Float,
    cy: Float,
    scopeR: Float,
    rangeNm: Double,
): Offset {
    val rad = Math.toRadians(rotationDeg.toDouble())
    val east = at.east * cos(rad) + at.north * sin(rad)
    val north = at.north * cos(rad) - at.east * sin(rad)
    return Offset(
        cx + (east / rangeNm * scopeR).toFloat(),
        cy - (north / rangeNm * scopeR).toFloat(),
    )
}

private fun projectLatLng(
    origin: LatLng,
    point: LatLng,
    rotationDeg: Float,
    cx: Float,
    cy: Float,
    scopeR: Float,
    rangeNm: Double,
): Offset = projectNorthEast(
    NorthEast(
        (point.lat - origin.lat) * 60.0,
        (point.lon - origin.lon) * 60.0 * cos(Math.toRadians(origin.lat)),
    ),
    rotationDeg, cx, cy, scopeR, rangeNm,
)

/** Screen position for hit-testing, matching exactly what the draw pass plots. */
private fun projectAircraft(
    origin: LatLng,
    ac: Aircraft,
    nowNanos: Long,
    rotationDeg: Float,
    cx: Float,
    cy: Float,
    scopeR: Float,
    rangeNm: Double,
): Offset? {
    val at = deadReckon(origin, ac, nowNanos) ?: return null
    if (hypot(at.north, at.east) > rangeNm) return null
    return projectNorthEast(at, rotationDeg, cx, cy, scopeR, rangeNm)
}
