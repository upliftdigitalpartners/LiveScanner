package dev.fahim.livescanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tile placement decides whether the weather overlay lands on the right piece of ground. The
 * reference values below are the standard Web Mercator ones, so a regression here is unambiguous.
 */
class MercatorTilesTest {

    @Test
    fun `origin of the tile grid is the north-west corner of the world`() {
        assertEquals(0.0, MercatorTiles.lonToTileX(-180.0, 4), 1e-9)
        assertEquals(0.0, MercatorTiles.latToTileY(85.05112878, 4), 1e-6)
    }

    @Test
    fun `the prime meridian and equator sit at the middle of the grid`() {
        val span = 1 shl 4
        assertEquals(span / 2.0, MercatorTiles.lonToTileX(0.0, 4), 1e-9)
        assertEquals(span / 2.0, MercatorTiles.latToTileY(0.0, 4), 1e-9)
    }

    @Test
    fun `projection round-trips`() {
        for (lat in listOf(-60.0, -12.5, 0.0, 35.214, 47.6, 71.0)) {
            for (z in listOf(4, 7, 9)) {
                val y = MercatorTiles.latToTileY(lat, z)
                assertEquals(lat, MercatorTiles.tileYToLat(y, z), 1e-6)
            }
        }
        for (lon in listOf(-179.0, -80.9431, 0.0, 13.4, 151.2)) {
            for (z in listOf(4, 7, 9)) {
                val x = MercatorTiles.lonToTileX(lon, z)
                assertEquals(lon, MercatorTiles.tileXToLon(x, z), 1e-9)
            }
        }
    }

    @Test
    fun `tile y grows southward`() {
        // A northern latitude must land on a smaller row index than a southern one.
        assertTrue(MercatorTiles.latToTileY(45.0, 7) < MercatorTiles.latToTileY(35.0, 7))
    }

    @Test
    fun `zoom tightens as the scope range shrinks`() {
        assertEquals(9, MercatorTiles.zoomFor(10f))
        assertEquals(8, MercatorTiles.zoomFor(20f))
        assertEquals(7, MercatorTiles.zoomFor(40f))
        assertEquals(6, MercatorTiles.zoomFor(80f))
    }

    @Test
    fun `coverage contains the tile the origin itself falls in`() {
        // Charlotte Douglas.
        val lat = 35.214
        val lon = -80.9431
        val zoom = MercatorTiles.zoomFor(40f)
        val tiles = MercatorTiles.covering(lat, lon, 40f, zoom)

        val originX = MercatorTiles.lonToTileX(lon, zoom).toInt()
        val originY = MercatorTiles.latToTileY(lat, zoom).toInt()
        assertTrue(
            "coverage must include the origin tile",
            tiles.any { it.x == originX && it.y == originY && it.z == zoom },
        )
    }

    @Test
    fun `every returned tile actually borders the requested area`() {
        val lat = 42.3656 // Boston
        val lon = -71.0096
        val range = 25f
        val zoom = MercatorTiles.zoomFor(range)
        val latSpan = range / 60.0
        val lonSpan = range / (60.0 * Math.cos(Math.toRadians(lat)))

        for (tile in MercatorTiles.covering(lat, lon, range, zoom)) {
            // The tile's box and the requested box must overlap on both axes.
            assertTrue("tile is north of the area", tile.southLat <= lat + latSpan + 1e-6)
            assertTrue("tile is south of the area", tile.northLat >= lat - latSpan - 1e-6)
            assertTrue("tile is east of the area", tile.westLon <= lon + lonSpan + 1e-6)
            assertTrue("tile is west of the area", tile.eastLon >= lon - lonSpan - 1e-6)
        }
    }

    @Test
    fun `a tile box is north-west to south-east`() {
        val tile = MercatorTiles.Tile(7, 36, 48)
        assertTrue(tile.northLat > tile.southLat)
        assertTrue(tile.eastLon > tile.westLon)
    }

    @Test
    fun `coverage is capped so a wide range cannot flood the network`() {
        val tiles = MercatorTiles.covering(35.214, -80.9431, 80f, 12, maxTiles = 8)
        assertTrue("cap must be honoured", tiles.size <= 8)
    }

    @Test
    fun `a zero range asks for nothing`() {
        assertTrue(MercatorTiles.covering(35.0, -80.0, 0f, 7).isEmpty())
    }

    @Test
    fun `tile spans shrink as zoom increases`() {
        val wide = MercatorTiles.Tile(4, 4, 6)
        val tight = MercatorTiles.Tile(9, 128, 192)
        assertTrue(
            abs(wide.eastLon - wide.westLon) > abs(tight.eastLon - tight.westLon),
        )
    }
}
