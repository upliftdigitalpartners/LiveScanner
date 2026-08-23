package dev.fahim.livescanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The 4-character code is load-bearing beyond display: offline failover groups an airport's feeds
 * by it, so a wrong derivation would silently break falling forward to a working mount.
 */
class FeedTest {

    private fun liveatc(id: String) = Feed(
        id = id,
        name = "Test",
        source = FeedSource.LIVEATC,
        type = FeedType.ATC,
    )

    @Test
    fun `an explicit code wins`() {
        assertEquals("KCLT", liveatc("liveatc:anything").copy(code = "kclt").displayCode)
    }

    @Test
    fun `a LiveATC mount yields its ICAO`() {
        assertEquals("KCLT", liveatc("liveatc:kclt_twr").displayCode)
        assertEquals("KBOS", liveatc("liveatc:kbos_gnd_twr").displayCode)
        assertEquals("KJFK", liveatc("liveatc:kjfk").displayCode)
    }

    @Test
    fun `a numbered mount still yields four characters`() {
        // "kaus1_twr" must not become "KAUS1" — the code is capped at four.
        assertEquals("KAUS", liveatc("liveatc:kaus1_twr").displayCode)
    }

    @Test
    fun `Charlotte's three mounts share one code, which is what failover groups on`() {
        val codes = listOf("liveatc:kclt_twr", "liveatc:kclt1", "liveatc:kclt2")
            .map { liveatc(it).displayCode }
            .toSet()
        assertEquals(setOf("KCLT"), codes)
    }

    @Test
    fun `non-LiveATC sources fall back to a source tag`() {
        assertEquals(
            "BCFY",
            Feed("bcfy:31143", "X", FeedSource.BROADCASTIFY, FeedType.SCANNER).displayCode,
        )
        assertEquals(
            "CUST",
            Feed("custom:abc", "X", FeedSource.CUSTOM, FeedType.OTHER).displayCode,
        )
    }

    @Test
    fun `distance is null without coordinates on either side`() {
        val noCoords = liveatc("liveatc:kclt_twr")
        assertNull(noCoords.distanceNmFrom(LatLng(35.0, -80.0)))
        val withCoords = noCoords.copy(lat = 35.214, lon = -80.9431)
        assertNull(withCoords.distanceNmFrom(null))
    }

    @Test
    fun `nautical miles are shorter than kilometres for the same leg`() {
        val clt = liveatc("liveatc:kclt_twr").copy(lat = 35.214, lon = -80.9431)
        val from = LatLng(35.8801, -78.7880) // Raleigh-Durham
        val km = clt.distanceKmFrom(from)!!
        val nm = clt.distanceNmFrom(from)!!
        assertTrue(nm < km)
        assertEquals(km / 1.852, nm, 1e-9)
    }

    @Test
    fun `Charlotte to Raleigh is roughly 130 nautical miles`() {
        val clt = liveatc("liveatc:kclt_twr").copy(lat = 35.214, lon = -80.9431)
        val nm = clt.distanceNmFrom(LatLng(35.8801, -78.7880))!!
        assertTrue("expected ~110-140 NM, got $nm", abs(nm - 125.0) < 20.0)
    }

    @Test
    fun `a feed is at zero distance from itself`() {
        val clt = liveatc("liveatc:kclt_twr").copy(lat = 35.214, lon = -80.9431)
        assertEquals(0.0, clt.distanceNmFrom(LatLng(35.214, -80.9431))!!, 1e-6)
    }
}
