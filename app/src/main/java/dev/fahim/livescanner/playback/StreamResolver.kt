package dev.fahim.livescanner.playback

import android.util.Base64
import dev.fahim.livescanner.data.Feed
import dev.fahim.livescanner.data.FeedRepository
import dev.fahim.livescanner.data.FeedSource

/**
 * Turns a [Feed] into something playable.
 *
 * - LiveATC / custom feeds carry a direct stream URL.
 * - Broadcastify feeds resolve to the Premium audio endpoint; the basic-auth header is
 *   injected by the data source only when credentials are stored (see ScannerPlaybackService).
 *   Without credentials this is best-effort and will fail unless a direct URL was supplied.
 */
object StreamResolver {

    fun resolveUri(feed: Feed): String? = when (feed.source) {
        FeedSource.LIVEATC, FeedSource.CUSTOM -> feed.streamUrl
        FeedSource.BROADCASTIFY ->
            feed.broadcastifyFeedId?.let { "https://audio.broadcastify.com/$it.mp3" } ?: feed.streamUrl
    }

    /** Basic-auth header for Broadcastify Premium, or null when no credentials are stored. */
    fun broadcastifyAuthHeader(repository: FeedRepository): String? {
        val user = repository.broadcastifyUsername() ?: return null
        val pass = repository.broadcastifyPassword() ?: return null
        val token = Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $token"
    }
}
