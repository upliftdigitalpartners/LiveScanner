package dev.fahim.livescanner.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import dev.fahim.livescanner.data.LiveAtc
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Opens a LiveATC mount on whichever edge server is actually carrying it.
 *
 * The catalog stores one URL per feed. When LiveATC rehomes a mount, that URL starts answering
 * 404 and every attempt to play the feed dies with a bare source error — which is what Charlotte
 * has been doing on all three of its mounts while feeds that happen to still live on the
 * catalogued host keep working. This source removes the assumption: on open it asks LiveATC's
 * playlist which host has the mount, and if that can't be reached it sweeps the known edges until
 * one answers.
 *
 * Non-LiveATC URIs pass straight through to [upstream], so nothing else changes shape.
 */
class LiveAtcDataSource(
    private val upstream: DataSource.Factory,
    private val playlist: PlaylistFetcher,
) : DataSource {

    /** Fetches a LiveATC `.pls` body, or returns null if it can't be read. */
    fun interface PlaylistFetcher {
        fun fetch(url: String): String?
    }

    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null
    private var openedUri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val original = dataSpec.uri.toString()
        val mount = LiveAtc.mountOf(original)
            ?: return openDirect(dataSpec).also { openedUri = dataSpec.uri }

        var lastFailure: IOException? = null
        var refusal: IOException? = null
        for (candidate in candidatesFor(mount, original)) {
            val spec = if (candidate == original) dataSpec else dataSpec.withUri(Uri.parse(candidate))
            try {
                val opened = openDirect(spec)
                // Remember the winner so the next tune-in — and the failover that follows a
                // dropout — goes straight to the host that answered instead of sweeping again.
                resolved[mount] = candidate
                openedUri = spec.uri
                return opened
            } catch (e: InterruptedIOException) {
                throw e // playback was cancelled; not a candidate failure
            } catch (e: IOException) {
                // This edge doesn't have the mount. Drop the half-open source and try the next.
                closeQuietly()
                lastFailure = e
                // A server that answered with a status told us something; a host that never
                // connected only tells us it wasn't there. Keep the informative one, because it
                // is what decides whether the user is shown "feed offline" or "check your
                // internet" — and a sweep that ends on a timeout must not be read as the phone
                // being offline when earlier edges answered a clean 404.
                if (refusal == null && e is HttpDataSource.InvalidResponseCodeException) refusal = e
            }
        }
        // Every edge refused it. A stale winner is the likeliest reason a sweep that used to
        // succeed now doesn't, so clear it and let the next attempt start from the playlist.
        resolved.remove(mount)
        throw refusal ?: lastFailure ?: IOException("No LiveATC edge answered for mount $mount")
    }

    private fun openDirect(dataSpec: DataSpec): Long {
        val source = upstream.createDataSource()
        listeners.forEach(source::addTransferListener)
        delegate = source
        return source.open(dataSpec)
    }

    private fun candidatesFor(mount: String, original: String): List<String> =
        LiveAtc.candidates(original, known = resolved[mount], fromPlaylist = playlistEntries(mount))

    private fun playlistEntries(mount: String): List<String> {
        val now = System.currentTimeMillis()
        val lastTry = playlistAttempts[mount]
        // A playlist fetch that failed is usually LiveATC being unreachable rather than the mount
        // being gone, so back off instead of paying that timeout on every single open.
        if (lastTry != null && now - lastTry < PLAYLIST_RETRY_MS) return emptyList()
        playlistAttempts[mount] = now
        val body = playlist.fetch(LiveAtc.playlistUrl(mount)) ?: return emptyList()
        val entries = LiveAtc.parsePlaylist(body)
        if (entries.isNotEmpty()) playlistAttempts.remove(mount)
        return entries
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (delegate ?: throw IOException("read() before open()")).read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri ?: openedUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }

    private fun closeQuietly() {
        try {
            delegate?.close()
        } catch (_: IOException) {
            // The open already failed; a failure to close the wreckage tells us nothing new.
        } finally {
            delegate = null
        }
    }

    companion object {
        /** How long to wait before retrying a playlist lookup that couldn't be read. */
        private const val PLAYLIST_RETRY_MS = 5 * 60 * 1000L

        /** mount -> the URL that last opened successfully. */
        private val resolved = ConcurrentHashMap<String, String>()

        private val playlistAttempts = ConcurrentHashMap<String, Long>()

        /** Testing seam: forget everything learned about where mounts live. */
        fun clearCache() {
            resolved.clear()
            playlistAttempts.clear()
        }
    }
}
