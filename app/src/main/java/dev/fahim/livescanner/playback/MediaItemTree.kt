package dev.fahim.livescanner.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.common.collect.ImmutableList
import dev.fahim.livescanner.data.Feed
import dev.fahim.livescanner.data.FeedRepository
import dev.fahim.livescanner.data.FeedType
import dev.fahim.livescanner.data.LocationProvider

/**
 * The browsable content tree Android Auto renders its own UI from.
 *
 * Driving constraints shape this, not decoration: three segments instead of five, at most six
 * cards in any list, and a grid layout so every target clears the 72dp minimum. A media app hands
 * the head unit content — the system draws it — so this file is the whole of what we control.
 *
 *   root
 *    ├─ Favorites
 *    ├─ Air Traffic
 *    └─ Scanner
 */
class MediaItemTree(
    private val repository: FeedRepository,
    private val locationProvider: LocationProvider,
) {

    fun rootItem(): MediaItem = browsable(ROOT_ID, "Live Scanner", null)

    /** Extras that tell Android Auto to lay the browse tree out as a grid. */
    fun contentStyleExtras(): Bundle = Bundle().apply {
        putBoolean(CONTENT_STYLE_SUPPORTED, true)
        putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID_ITEM)
        putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_GRID_ITEM)
    }

    fun rootChildren(): ImmutableList<MediaItem> = ImmutableList.copyOf(
        listOf(
            browsable(CAT_FAVORITES, "Favorites", "Your starred feeds"),
            browsable(CAT_ATC, "Air Traffic", "Nearest control towers"),
            browsable(CAT_SCANNER, "Scanner", "Police · fire · EMS"),
        ),
    )

    fun children(parentId: String): ImmutableList<MediaItem> {
        if (parentId == ROOT_ID) return rootChildren()
        val here = locationProvider.lastKnownLocation()
        val feeds = when (parentId) {
            CAT_FAVORITES -> repository.favoriteFeeds()
            // Nearest first: on the road the useful tower is the one you are driving past.
            CAT_ATC -> repository.nearbyFeeds(here).filter { it.type == FeedType.ATC }
            CAT_SCANNER -> repository.nearbyFeeds(here).filter { it.type == FeedType.SCANNER }
            else -> emptyList()
        }
        return ImmutableList.copyOf(feeds.take(CAR_MAX_ITEMS).map(::feedItem))
    }

    fun itemById(mediaId: String): MediaItem? = when {
        mediaId == ROOT_ID -> rootItem()
        mediaId.startsWith(CAT_PREFIX) -> rootChildren().firstOrNull { it.mediaId == mediaId }
        else -> repository.feedById(mediaId)?.let(::feedItem)
    }

    fun searchItems(query: String): ImmutableList<MediaItem> =
        ImmutableList.copyOf(repository.search(query).take(CAR_MAX_ITEMS).map(::feedItem))

    /** Turns a browse item (mediaId only) into a fully playable item carrying its stream URI. */
    fun resolveForPlayback(item: MediaItem): MediaItem {
        if (item.localConfiguration != null) return item // already has a URI
        // Normal taps carry a feed mediaId; voice "play X" carries only a search query.
        val feed = repository.feedById(item.mediaId)
            ?: item.requestMetadata.searchQuery?.toString()?.let { repository.search(it).firstOrNull() }
            ?: return item
        val uri = StreamResolver.resolveUri(feed) ?: return item
        return feedItemBuilder(feed).setUri(uri).build()
    }

    private fun feedItem(feed: Feed): MediaItem = feedItemBuilder(feed).build()

    private fun feedItemBuilder(feed: Feed): MediaItem.Builder {
        // The head unit gets the code and frequency, not the long name — it has to read at a glance.
        val metadata = MediaMetadata.Builder()
            .setTitle("${feed.displayCode} · ${feed.name}")
            .setSubtitle(listOfNotNull(feed.location, feed.frequency).joinToString(" · "))
            .setArtist(feed.location ?: feed.subtitle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build()
        return MediaItem.Builder()
            .setMediaId(feed.id)
            .setMediaMetadata(metadata)
    }

    private fun browsable(id: String, title: String, subtitle: String?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        const val ROOT_ID = "root"
        const val CAT_PREFIX = "cat:"
        const val CAT_ATC = "cat:atc"
        const val CAT_SCANNER = "cat:scanner"
        const val CAT_FAVORITES = "cat:favorites"

        /** Android Auto's driver-distraction cap: six items per list, no exceptions. */
        const val CAR_MAX_ITEMS = 6

        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE_HINT =
            "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE_HINT =
            "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_GRID_ITEM = 2
    }
}
