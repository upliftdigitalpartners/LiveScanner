package dev.fahim.livescanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.fahim.livescanner.data.Aircraft
import dev.fahim.livescanner.ui.theme.B612Mono
import dev.fahim.livescanner.ui.theme.FdDim
import dev.fahim.livescanner.ui.theme.FdType
import dev.fahim.livescanner.ui.theme.FlightDeck
import dev.fahim.livescanner.ui.theme.altitudeRamp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

private const val CEILING_FT = 40_000f
private val GRID_FT = listOf(5_000, 10_000, 20_000, 40_000)

/** One contact reduced to what the profile plots: how far out, how high, what colour. */
private class ProfileTarget(
    val distanceFraction: Float,
    val altitudeFt: Int?,
    val color: Color,
    val selected: Boolean,
)

/**
 * Height on distance: the traffic seen side-on, 0 nm at the left edge and [rangeNm] at the right.
 *
 * The plan-view scope cannot show an approach stack — four aircraft strung down the glideslope land
 * on the same pixels there. This strip is the other axis: it makes the stack, and anyone descending
 * into it, obvious at a glance.
 *
 * Altitude is on a square-root scale. Linear to 40,000 ft crushes the 0-4,000 ft band where every
 * arrival and departure actually lives into the bottom row of pixels.
 */
@Composable
fun SideProfile(
    aircraft: List<Aircraft>,
    centerLat: Double,
    centerLon: Double,
    rangeNm: Float,
    modifier: Modifier = Modifier,
    selectedHex: String? = null,
) {
    val p = FlightDeck
    val measurer = rememberTextMeasurer()

    // Distances and colours are resolved once per ADS-B update, not once per draw.
    val targets = remember(aircraft, centerLat, centerLon, rangeNm, selectedHex, p) {
        val range = rangeNm.toDouble()
        if (range <= 0.0) {
            emptyList()
        } else {
            aircraft.mapNotNull { ac ->
                val groundNm = hypot(
                    (ac.lat - centerLat) * 60.0,
                    (ac.lon - centerLon) * 60.0 * cos(Math.toRadians(centerLat)),
                )
                if (groundNm > range) return@mapNotNull null
                ProfileTarget(
                    distanceFraction = (groundNm / range).toFloat(),
                    altitudeFt = ac.altitudeFt,
                    color = if (ac.category == "A7") p.amber else altitudeRamp(ac.altitudeFt),
                    selected = ac.hex == selectedHex,
                )
            }
        }
    }

    val gridStyle = remember(p.textGhost) {
        TextStyle(fontFamily = B612Mono, fontSize = FdType.dataTag, color = p.textGhost)
    }
    val gridLabels = remember(gridStyle) {
        GRID_FT.map { measurer.measure("${it / 1_000}K", gridStyle) }
    }
    val diamond = remember { Path() }

    Column(modifier) {
        SectionLabel("PROFILE · ${targets.size} CONTACTS")
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(90.dp)
                .clip(RoundedCornerShape(FdDim.radiusRow))
                .background(p.panelAlt)
                .border(1.dp, p.strokeDim, RoundedCornerShape(FdDim.radiusRow)),
        ) {
            val padLeft = 8.dp.toPx()
            val labelGutter = 26.dp.toPx()
            val plotRight = size.width - labelGutter
            val plotWidth = plotRight - padLeft
            val ceilingY = 9.dp.toPx()
            val groundY = size.height - 9.dp.toPx()
            if (plotWidth <= 0f || groundY <= ceilingY) return@Canvas

            // Square-root altitude scale: 0 ft on the ground line, 40,000 ft on the top line.
            fun yOf(altitudeFt: Int): Float {
                val t = sqrt((altitudeFt.coerceIn(0, CEILING_FT.toInt())) / CEILING_FT)
                return groundY - t * (groundY - ceilingY)
            }

            GRID_FT.forEachIndexed { index, ft ->
                val y = yOf(ft)
                drawLine(
                    color = p.stroke.copy(alpha = 0.35f),
                    start = Offset(padLeft, y),
                    end = Offset(plotRight, y),
                    strokeWidth = 1f,
                )
                val layout = gridLabels[index]
                drawText(
                    layout,
                    color = p.textGhost,
                    topLeft = Offset(
                        size.width - 6.dp.toPx() - layout.size.width,
                        y - layout.size.height / 2f,
                    ),
                )
            }

            // The field itself: the ground line, with a tick at the threshold of the strip.
            drawLine(
                color = p.stroke,
                start = Offset(padLeft, groundY),
                end = Offset(plotRight, groundY),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                color = p.green,
                start = Offset(padLeft, groundY),
                end = Offset(padLeft, groundY - 7.dp.toPx()),
                strokeWidth = 1.5.dp.toPx(),
            )

            val half = 7.dp.toPx() / 2f
            val groundMark = Size(2.dp.toPx(), 4.dp.toPx())
            for (target in targets) {
                val x = padLeft + target.distanceFraction * plotWidth

                // No altitude means it is on the field: a tick on the ground line, not a target.
                val altitude = target.altitudeFt
                if (altitude == null) {
                    drawRect(
                        color = p.textFaint,
                        topLeft = Offset(x - groundMark.width / 2f, groundY - groundMark.height),
                        size = groundMark,
                    )
                    continue
                }

                val y = yOf(altitude)
                // One reused Path, so the plot allocates nothing however busy the airspace gets.
                diamond.rewind()
                diamond.moveTo(x, y - half)
                diamond.lineTo(x + half, y)
                diamond.lineTo(x, y + half)
                diamond.lineTo(x - half, y)
                diamond.close()
                drawPath(diamond, color = target.color)

                // White ring, matching how the scope marks the same selection.
                if (target.selected) {
                    drawCircle(
                        color = Color.White,
                        radius = half * 1.9f,
                        center = Offset(x, y),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
        }
    }
}
