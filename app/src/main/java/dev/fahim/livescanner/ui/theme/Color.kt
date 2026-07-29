package dev.fahim.livescanner.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Flight Deck design tokens. Hex values are fixed by the design spec — do not "improve" them.
 *
 * Night is not a filter over Day: it is a second palette that collapses the accent hues onto a
 * red/amber monochrome so the screen stays usable as a red-light cockpit instrument at night.
 */
@Immutable
data class FlightDeckPalette(
    val night: Boolean,
    // Surfaces
    val bg: Color,
    val panel: Color,
    val panelAlt: Color,
    // Lines
    val stroke: Color,
    val strokeDim: Color,
    val strokeInput: Color,
    // Type
    val textHi: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val textGhost: Color,
    // Accents
    val cyan: Color,
    val cyanBorder: Color,
    val cyanBg: Color,
    val green: Color,
    val greenBorder: Color,
    val greenBg: Color,
    val amber: Color,
    val amberBorder: Color,
    val magenta: Color,
    val magentaBorder: Color,
    val red: Color,
    val redBorder: Color,
)

val DayPalette = FlightDeckPalette(
    night = false,
    bg = Color(0xFF05080C),
    panel = Color(0xFF0A1016),
    panelAlt = Color(0xFF070D13),
    stroke = Color(0xFF22303D),
    strokeDim = Color(0xFF131C25),
    strokeInput = Color(0xFF1A2530),
    textHi = Color(0xFFE8F2FA),
    text = Color(0xFFC9D6E2),
    textDim = Color(0xFF7D8FA0),
    textFaint = Color(0xFF5A6B7A),
    textGhost = Color(0xFF42536A),
    cyan = Color(0xFF00E5FF),
    cyanBorder = Color(0xFF114D59),
    cyanBg = Color(0xFF071A1F),
    green = Color(0xFF00E676),
    greenBorder = Color(0xFF2C5B46),
    greenBg = Color(0xFF0A1A13),
    amber = Color(0xFFFFB300),
    amberBorder = Color(0xFF3D3322),
    magenta = Color(0xFFFF4FD8),
    magentaBorder = Color(0xFF5C1D4C),
    red = Color(0xFFFF3B30),
    redBorder = Color(0xFF6B1D1D),
)

/**
 * Red-light treatment. Background stays near-black; every accent folds into red/amber so nothing
 * on screen emits the blue-green light that wrecks night vision.
 */
val NightPalette = FlightDeckPalette(
    night = true,
    bg = Color(0xFF05080C),
    panel = Color(0xFF0F0809),
    panelAlt = Color(0xFF0B0506),
    stroke = Color(0xFF3D2222),
    strokeDim = Color(0xFF251313),
    strokeInput = Color(0xFF301A1A),
    textHi = Color(0xFFFFC9A0),
    text = Color(0xFFE0A070),
    textDim = Color(0xFFA06844),
    textFaint = Color(0xFF7A4E33),
    textGhost = Color(0xFF573828),
    cyan = Color(0xFFFF8A3D),
    cyanBorder = Color(0xFF5C2E14),
    cyanBg = Color(0xFF1A0C05),
    green = Color(0xFFFFB300),
    greenBorder = Color(0xFF5B4326),
    greenBg = Color(0xFF1A1105),
    amber = Color(0xFFFFB300),
    amberBorder = Color(0xFF3D3322),
    magenta = Color(0xFFFF6B4A),
    magentaBorder = Color(0xFF5C2A1D),
    red = Color(0xFFFF3B30),
    redBorder = Color(0xFF6B1D1D),
)

/**
 * Radar target color by altitude. The spec gives both a pair of endpoints and an explicit formula;
 * they disagree at the top end, and the formula is what the shipping scope already used, so the
 * formula wins: rgb(255-182t, 179+31t, 0+255t) for t = alt/40000 clamped.
 */
fun altitudeRamp(altitudeFt: Int?): Color {
    if (altitudeFt == null) return Color(0xFF9AA0A6)
    val t = (altitudeFt.coerceIn(0, 40_000)) / 40_000f
    return Color(
        red = (255f - 182f * t) / 255f,
        green = (179f + 31f * t) / 255f,
        blue = (255f * t) / 255f,
    )
}
