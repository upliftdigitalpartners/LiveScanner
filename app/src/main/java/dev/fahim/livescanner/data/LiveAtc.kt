package dev.fahim.livescanner.data

/**
 * LiveATC's URL layout.
 *
 * A LiveATC feed is a *mount* name (`kclt_twr`), not a URL. Mounts are spread across a pool of
 * edge servers and LiveATC moves them between servers as volunteers come and go, so a hardcoded
 * `https://d.liveatc.net/<mount>` is only right until the next time a feed is rehomed — after
 * that the host answers 404 and the player reports a bare source error even though the feed is
 * perfectly alive on a sibling host.
 *
 * The authoritative pointer is the per-mount playlist LiveATC's own web player fetches. Resolve
 * that and you get the host the mount is actually on right now; the static host list below is
 * only the backstop for when the playlist itself is unreachable.
 *
 * Everything here is pure string work so it can be tested without a network.
 */
object LiveAtc {

    /** Serves the per-mount `.pls` playlists. */
    private const val PLAYLIST_HOST = "www.liveatc.net"

    /**
     * Known stream edges, in the order they are worth trying. `d` is first because it hosts the
     * majority of mounts and is what the catalog has always pointed at.
     */
    val EDGE_HOSTS = listOf(
        "d.liveatc.net",
        "dd.liveatc.net",
        "s1-bos.liveatc.net",
        "s1-fmt2.liveatc.net",
        "hd.liveatc.net",
    )

    fun isLiveAtc(url: String): Boolean = hostOf(url)?.endsWith("liveatc.net") == true

    /**
     * The mount name inside a LiveATC URL, or null if this isn't one.
     *
     * Tolerates the playlist suffixes and the cache-busting query LiveATC appends to its own
     * playlist entries, so a URL that came back from [parsePlaylist] round-trips.
     */
    fun mountOf(url: String): String? {
        if (!isLiveAtc(url)) return null
        val path = url.substringAfter("://").substringAfter('/', "")
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
        if (path.isEmpty()) return null
        // `/play/kclt_twr.pls` and `/kclt_twr` both name the same mount.
        val last = path.substringAfterLast('/')
        val mount = last.removeSuffix(".pls").removeSuffix(".m3u")
        return mount.lowercase().ifEmpty { null }
    }

    /** Where to ask LiveATC which host currently carries [mount]. */
    fun playlistUrl(mount: String): String = "https://$PLAYLIST_HOST/play/$mount.pls"

    /**
     * Stream URLs out of a `.pls` or `.m3u` body, in playlist order.
     *
     * PLS numbers its entries (`File1=`, `File2=`) and the numbering is authoritative rather than
     * the line order, so entries are sorted by index. M3U is a bare URL list with `#` comments.
     */
    fun parsePlaylist(body: String): List<String> {
        val numbered = mutableListOf<Pair<Int, String>>()
        val bare = mutableListOf<String>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(";")) continue
            val match = FILE_ENTRY.matchEntire(line)
            if (match != null) {
                val index = match.groupValues[1].toIntOrNull() ?: continue
                val url = match.groupValues[2].trim()
                if (url.isNotEmpty()) numbered += index to url
                continue
            }
            if (line.startsWith("#")) continue
            if (line.startsWith("http://", true) || line.startsWith("https://", true)) bare += line
        }
        val ordered = numbered.sortedBy { it.first }.map { it.second } + bare
        return ordered.distinct()
    }

    /**
     * Every URL worth trying for [streamUrl], best first.
     *
     * Order is the whole point: a [known] host that opened last time costs one request, the
     * mount's own [fromPlaylist] entries are what LiveATC says is current, and only then is it
     * worth paying a connect timeout each for the static edge list. Duplicates are dropped so a
     * host never gets dialled twice in one sweep.
     */
    fun candidates(
        streamUrl: String,
        known: String? = null,
        fromPlaylist: List<String> = emptyList(),
    ): List<String> {
        val mount = mountOf(streamUrl) ?: return listOf(streamUrl)
        val fromHosts = EDGE_HOSTS.map { "https://$it/$mount" }
        return (listOfNotNull(known) + fromPlaylist + streamUrl + fromHosts).distinct()
    }

    private fun hostOf(url: String): String? =
        url.substringAfter("://", "").substringBefore('/').substringBefore(':')
            .lowercase().ifEmpty { null }

    private val FILE_ENTRY = Regex("""(?i)file(\d+)\s*=\s*(.*)""")
}
