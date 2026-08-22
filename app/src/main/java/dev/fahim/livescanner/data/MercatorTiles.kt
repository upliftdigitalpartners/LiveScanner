package dev.fahim.livescanner.data

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web Mercator tile arithmetic, for laying slippy-map imagery over the scope.
 *
 * The scope is not a map: it is a range-ring plan view in nautical miles that can be rotated and
 * panned. Tiles therefore have to be placed by their geographic corners and projected the same way
 * contacts are. Over the 5-80 NM the scope covers, the difference between Mercator's latitude
 * scaling and the scope's flat projection is far below one pixel, so each tile is placed as a
 * simple rectangle between its north-west and south-east corners.
 */
object MercatorTiles {

    /** One tile reference plus the geographic box it covers. */
    data class Tile(val z: Int, val x: Int, val y: Int) {
        val northLat: Double get() = tileYToLat(y.toDouble(), z)
        val southLat: Double get() = tileYToLat((y + 1).toDouble(), z)
        val westLon: Double get() = tileXToLon(x.toDouble(), z)
        val eastLon: Double get() = tileXToLon((x + 1).toDouble(), z)
    }

    /**
     * Zoom chosen from range alone rather than from pixel density. Radar imagery is coarse — a few
     * kilometres per pixel — so matching it exactly to the display buys nothing, and keeping the
     * choice independent of layout means the tile set doesn't churn while the scope is resized.
     */
    fun zoomFor(rangeNm: Float): Int = when {
        rangeNm <= 12f -> 9
        rangeNm <= 25f -> 8
        rangeNm <= 50f -> 7
        else -> 6
    }

    /**
     * Every tile touching the square that circumscribes the scope, capped so a wide range can't
     * ask for an unreasonable number of downloads.
     */
    fun covering(
        originLat: Double,
        originLon: Double,
        rangeNm: Float,
        zoom: Int,
        maxTiles: Int = 24,
    ): List<Tile> {
        if (rangeNm <= 0f) return emptyList()
        val latSpan = rangeNm / 60.0
        val cosLat = cos(Math.toRadians(originLat)).coerceAtLeast(0.01)
        val lonSpan = rangeNm / (60.0 * cosLat)

        val north = (originLat + latSpan).coerceAtMost(MAX_LAT)
        val south = (originLat - latSpan).coerceAtLeast(-MAX_LAT)
        val west = originLon - lonSpan
        val east = originLon + lonSpan

        val span = 1 shl zoom
        val xMin = floor(lonToTileX(west, zoom)).toInt().coerceIn(0, span - 1)
        val xMax = floor(lonToTileX(east, zoom)).toInt().coerceIn(0, span - 1)
        // Tile y grows southward, so the northern edge yields the smaller index.
        val yMin = floor(latToTileY(north, zoom)).toInt().coerceIn(0, span - 1)
        val yMax = floor(latToTileY(south, zoom)).toInt().coerceIn(0, span - 1)

        val tiles = ArrayList<Tile>()
        for (y in yMin..yMax) {
            for (x in xMin..xMax) {
                if (tiles.size >= maxTiles) return tiles
                tiles.add(Tile(zoom, x, y))
            }
        }
        return tiles
    }

    fun lonToTileX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)

    fun latToTileY(lat: Double, z: Int): Double {
        val rad = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * (1 shl z)
    }

    fun tileXToLon(x: Double, z: Int): Double = x / (1 shl z) * 360.0 - 180.0

    fun tileYToLat(y: Double, z: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl z)
        return Math.toDegrees(atan(sinh(n)))
    }

    /** Mercator is undefined at the poles; this is the standard cut-off. */
    private const val MAX_LAT = 85.05112878
}
