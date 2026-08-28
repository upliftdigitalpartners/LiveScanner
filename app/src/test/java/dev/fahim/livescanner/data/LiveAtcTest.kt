package dev.fahim.livescanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAtcTest {

    // ── Recognising LiveATC URLs ─────────────────────────────────────────────────────────────

    @Test
    fun `every LiveATC edge is recognised, whichever one the catalog points at`() {
        assertTrue(LiveAtc.isLiveAtc("https://d.liveatc.net/kclt_twr"))
        assertTrue(LiveAtc.isLiveAtc("https://s1-fmt2.liveatc.net/kclt_twr"))
        assertTrue(LiveAtc.isLiveAtc("http://www.liveatc.net/play/kclt_twr.pls"))
    }

    @Test
    fun `other stream hosts are left alone`() {
        assertFalse(LiveAtc.isLiveAtc("https://audio.broadcastify.com/1234.mp3"))
        assertFalse(LiveAtc.isLiveAtc("https://example.com/liveatc.net"))
        assertFalse(LiveAtc.isLiveAtc("not a url"))
    }

    // ── Pulling the mount out ────────────────────────────────────────────────────────────────

    @Test
    fun `the mount is the last path segment`() {
        assertEquals("kclt_twr", LiveAtc.mountOf("https://d.liveatc.net/kclt_twr"))
        assertEquals("kclt1", LiveAtc.mountOf("https://d.liveatc.net/kclt1"))
    }

    @Test
    fun `a playlist URL names the same mount as the stream URL`() {
        val mount = LiveAtc.mountOf("https://d.liveatc.net/kclt_twr")!!
        assertEquals(mount, LiveAtc.mountOf(LiveAtc.playlistUrl(mount)))
    }

    @Test
    fun `LiveATC's own cache-busting query survives a round trip`() {
        // Entries that come back from a .pls carry ?nocache=NNNN; feeding one back in has to
        // resolve to the same mount, or the resolved-host cache would key on a new mount each
        // time and re-sweep on every single open.
        assertEquals(
            "kclt_twr",
            LiveAtc.mountOf("https://s1-fmt2.liveatc.net/kclt_twr?nocache=1783453"),
        )
    }

    @Test
    fun `mounts are lowercased so case in the catalog can't split the cache`() {
        assertEquals("kclt_twr", LiveAtc.mountOf("https://d.liveatc.net/KCLT_TWR"))
    }

    @Test
    fun `a non-LiveATC or empty URL has no mount`() {
        assertNull(LiveAtc.mountOf("https://audio.broadcastify.com/1234.mp3"))
        assertNull(LiveAtc.mountOf("https://d.liveatc.net/"))
        assertNull(LiveAtc.mountOf("https://d.liveatc.net"))
    }

    // ── Playlist parsing ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a real pls yields the stream URL`() {
        val body = """
            [playlist]
            NumberOfEntries=1
            File1=https://s1-fmt2.liveatc.net/kclt_twr?nocache=1783453
            Title1=KCLT Charlotte Tower
            Length1=-1
            Version=2
        """.trimIndent()
        assertEquals(
            listOf("https://s1-fmt2.liveatc.net/kclt_twr?nocache=1783453"),
            LiveAtc.parsePlaylist(body),
        )
    }

    @Test
    fun `entries come back in index order, not line order`() {
        // A mount served by two edges lists both; File1 is the one LiveATC wants tried first,
        // and it is not always written first.
        val body = """
            [playlist]
            File2=https://dd.liveatc.net/kclt_twr
            File1=https://d.liveatc.net/kclt_twr
            NumberOfEntries=2
        """.trimIndent()
        assertEquals(
            listOf("https://d.liveatc.net/kclt_twr", "https://dd.liveatc.net/kclt_twr"),
            LiveAtc.parsePlaylist(body),
        )
    }

    @Test
    fun `an m3u body parses too, comments skipped`() {
        val body = """
            #EXTM3U
            #EXTINF:-1,KCLT Tower
            https://d.liveatc.net/kclt_twr
        """.trimIndent()
        assertEquals(listOf("https://d.liveatc.net/kclt_twr"), LiveAtc.parsePlaylist(body))
    }

    @Test
    fun `duplicate entries collapse`() {
        val body = """
            [playlist]
            File1=https://d.liveatc.net/kclt_twr
            File2=https://d.liveatc.net/kclt_twr
        """.trimIndent()
        assertEquals(1, LiveAtc.parsePlaylist(body).size)
    }

    @Test
    fun `an error page instead of a playlist yields nothing rather than junk candidates`() {
        // www.liveatc.net answers a bad mount with HTML. Parsing that must not hand the player
        // a pile of markup to try and open.
        val body = "<html><head><title>404 Not Found</title></head><body>Not here</body></html>"
        assertEquals(emptyList<String>(), LiveAtc.parsePlaylist(body))
        assertEquals(emptyList<String>(), LiveAtc.parsePlaylist(""))
    }

    // ── Candidate ordering ───────────────────────────────────────────────────────────────────

    @Test
    fun `the catalog URL is tried first`() {
        val candidates = LiveAtc.candidates("https://d.liveatc.net/kclt_twr")
        assertEquals("https://d.liveatc.net/kclt_twr", candidates.first())
    }

    @Test
    fun `the same mount is offered on every other edge`() {
        val candidates = LiveAtc.candidates("https://d.liveatc.net/kclt_twr")
        assertTrue(candidates.containsAll(LiveAtc.EDGE_HOSTS.map { "https://$it/kclt_twr" }))
        assertTrue(candidates.all { LiveAtc.mountOf(it) == "kclt_twr" })
    }

    @Test
    fun `the catalog host is not tried twice`() {
        val candidates = LiveAtc.candidates("https://d.liveatc.net/kclt_twr")
        assertEquals(candidates.size, candidates.distinct().size)
        assertEquals(LiveAtc.EDGE_HOSTS.size, candidates.size)
    }

    @Test
    fun `a catalog URL on an unlisted edge still gets the whole sweep`() {
        val candidates = LiveAtc.candidates("https://s9-unknown.liveatc.net/kclt_twr")
        assertEquals("https://s9-unknown.liveatc.net/kclt_twr", candidates.first())
        assertEquals(LiveAtc.EDGE_HOSTS.size + 1, candidates.size)
    }

    @Test
    fun `a non-LiveATC URL is its own only candidate`() {
        val url = "https://audio.broadcastify.com/1234.mp3"
        assertEquals(listOf(url), LiveAtc.candidates(url))
    }

    // ── Ordering once we know something ──────────────────────────────────────────────────────

    @Test
    fun `a host known to work is dialled first`() {
        val candidates = LiveAtc.candidates(
            "https://d.liveatc.net/kclt_twr",
            known = "https://s1-fmt2.liveatc.net/kclt_twr",
        )
        assertEquals("https://s1-fmt2.liveatc.net/kclt_twr", candidates.first())
    }

    @Test
    fun `playlist entries outrank the static edge list`() {
        val playlist = listOf("https://s1-bos.liveatc.net/kclt_twr?nocache=99")
        val candidates = LiveAtc.candidates("https://d.liveatc.net/kclt_twr", fromPlaylist = playlist)
        assertEquals(playlist.first(), candidates.first())
        assertTrue(candidates.indexOf("https://d.liveatc.net/kclt_twr") > 0)
    }

    @Test
    fun `a known host is not dialled a second time further down the sweep`() {
        // The known host is normally one of the static edges. Trying it twice would double the
        // wait before reaching a host that might actually work.
        val known = "https://dd.liveatc.net/kclt_twr"
        val candidates = LiveAtc.candidates("https://d.liveatc.net/kclt_twr", known = known)
        assertEquals(1, candidates.count { it == known })
        assertEquals(candidates.size, candidates.distinct().size)
    }

    @Test
    fun `known and playlist agreeing does not lengthen the sweep`() {
        val url = "https://s1-fmt2.liveatc.net/kclt_twr"
        val candidates = LiveAtc.candidates(
            "https://d.liveatc.net/kclt_twr",
            known = url,
            fromPlaylist = listOf(url),
        )
        assertEquals(1, candidates.count { it == url })
    }

    @Test
    fun `the catalog URL is still tried even when the playlist disagrees with it`() {
        // If LiveATC's playlist is stale or wrong, the URL that shipped with the app is the
        // fallback — dropping it would make the playlist a single point of failure.
        val candidates = LiveAtc.candidates(
            "https://d.liveatc.net/kclt_twr",
            fromPlaylist = listOf("https://s1-bos.liveatc.net/kclt_twr"),
        )
        assertTrue(candidates.contains("https://d.liveatc.net/kclt_twr"))
    }

    @Test
    fun `all three Charlotte mounts sweep independently`() {
        val mounts = listOf("kclt_twr", "kclt1", "kclt2")
        for (mount in mounts) {
            val candidates = LiveAtc.candidates("https://d.liveatc.net/$mount")
            assertEquals(LiveAtc.EDGE_HOSTS.size, candidates.size)
            assertTrue(candidates.all { it.endsWith("/$mount") })
        }
    }
}
