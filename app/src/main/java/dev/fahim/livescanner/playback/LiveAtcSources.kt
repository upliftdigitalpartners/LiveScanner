package dev.fahim.livescanner.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Builds the data sources that know how to find a LiveATC mount.
 *
 * Both radios go through here — COMM 1 in the playback service and COMM 2 in [SecondaryRadio] —
 * so a feed that plays on one plays on the other.
 */
object LiveAtcSources {

    const val CONNECT_TIMEOUT_MS = 8_000
    const val READ_TIMEOUT_MS = 20_000

    /** A `.pls` is a few hundred bytes; anything larger isn't the playlist we asked for. */
    private const val MAX_PLAYLIST_BYTES = 64 * 1024

    /** LiveATC's web host turns away obvious bots, so the playlist request looks like a browser. */
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * The streaming factory. The connect timeout is short enough that sweeping a handful of edges
     * for a rehomed mount stays inside a few seconds; the read timeout is what protects a stream
     * once it is actually up.
     */
    fun streamFactory(): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent("LiveScanner/0.1 (Android)")
        .setAllowCrossProtocolRedirects(true) // LiveATC 302-redirects to a node host
        .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
        .setReadTimeoutMs(READ_TIMEOUT_MS)

    /** Kept separate from [streamFactory] so the feeds' own User-Agent doesn't change. */
    private fun playlistFactory(): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent(BROWSER_USER_AGENT)
        .setDefaultRequestProperties(mapOf("Referer" to "https://www.liveatc.net/"))
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
        .setReadTimeoutMs(CONNECT_TIMEOUT_MS)

    /**
     * Wraps [upstream] so LiveATC URIs are opened on whichever edge currently carries the mount.
     * Everything else passes through untouched.
     */
    fun resolving(upstream: DataSource.Factory): DataSource.Factory {
        val playlists = playlistFactory()
        return DataSource.Factory {
            LiveAtcDataSource(upstream) { url -> readText(playlists, url) }
        }
    }

    /** Reads a small text resource through a Media3 source, or null if it can't be fetched. */
    private fun readText(factory: DataSource.Factory, url: String): String? {
        val source = factory.createDataSource()
        return try {
            source.open(DataSpec(Uri.parse(url)))
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (out.size() < MAX_PLAYLIST_BYTES) {
                val read = source.read(chunk, 0, chunk.size)
                if (read == C.RESULT_END_OF_INPUT) break
                out.write(chunk, 0, read)
            }
            out.toString(Charsets.UTF_8.name())
        } catch (e: IOException) {
            null
        } finally {
            try {
                source.close()
            } catch (_: IOException) {
                // Nothing useful left to do with a playlist fetch that already failed.
            }
        }
    }
}
