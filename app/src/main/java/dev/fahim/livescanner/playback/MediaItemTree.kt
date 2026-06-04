package dev.fahim.livescanner.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.common.collect.ImmutableList
import dev.fahim.livescanner.data.Feed
import dev.fahim.livescanner.data.FeedRepository
import dev.fahim.livescanner.data.FeedType
import dev.fahim.livescanner.data.LocationProvider

/**
 * Builds the browsable content tree consumed by MediaBrowser clients — most importantly
 * Android Auto, which renders its own UI directly from these MediaItems.
 *
 *   root
 *    ├─ Nearby      (feeds sorted by distance to last-known location)
 *    ├─ Air Traffic (FeedType.ATC)
 *    ├─ Scanner     (FeedType.SCANNER)
 *    ├─ Favorites
 *    └─ All Feeds
 */
class MediaItemTree(
    private val repository: FeedRepository,
    private val locationProvider: LocationProvider,
) {

    fun rootItem(): MediaItem = browsable(ROOT_ID, "Live Scanner", null)

    fun rootChildren(): ImmutableList<MediaItem> = ImmutableList.copyOf(
        listOf(
            browsable(CAT_NEARBY, "Nearby", "Closest feeds to you"),
            browsable(CAT_ATC, "Air Traffic", "Airport control towers"),
            browsable(CAT_SCANNER, "Scanner", "Police · fire · EMS"),
            browsable(CAT_FAVORITES, "Favorites", null),
            browsable(CAT_ALL, "All Feeds", null),
        ),
    )

    fun children(parentId: String): ImmutableList<MediaItem> {
        if (parentId == ROOT_ID) return rootChildren()
        val feeds = when (parentId) {
            CAT_NEARBY -> repository.nearbyFeeds(locationProvider.lastKnownLocation())
            CAT_ATC -> repository.feedsByType(FeedType.ATC)
            CAT_SCANNER -> repository.feedsByType(FeedType.SCANNER)
            CAT_FAVORITES -> repository.favoriteFeeds()
            CAT_ALL -> repository.allFeeds.value.sortedBy { it.name }
            else -> emptyList()
        }
        return ImmutableList.copyOf(feeds.map(::feedItem))
    }

    fun itemById(mediaId: String): MediaItem? = when {
        mediaId == ROOT_ID -> rootItem()
        mediaId.startsWith(CAT_PREFIX) -> rootChildren().firstOrNull { it.mediaId == mediaId }
        else -> repository.feedById(mediaId)?.let(::feedItem)
    }

    fun searchItems(query: String): ImmutableList<MediaItem> =
        ImmutableList.copyOf(repository.search(query).map(::feedItem))

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
        val metadata = MediaMetadata.Builder()
            .setTitle(feed.name)
            .setSubtitle(feed.subtitle)
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
        const val CAT_NEARBY = "cat:nearby"
        const val CAT_ATC = "cat:atc"
        const val CAT_SCANNER = "cat:scanner"
        const val CAT_FAVORITES = "cat:favorites"
        const val CAT_ALL = "cat:all"
    }
}
