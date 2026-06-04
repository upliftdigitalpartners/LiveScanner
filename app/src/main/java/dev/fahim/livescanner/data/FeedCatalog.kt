package dev.fahim.livescanner.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.decodeFromString

/** Loads the bundled, read-only feed catalog from assets/feeds.json. */
class FeedCatalog(private val context: Context) {

    fun load(): List<Feed> = try {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        AppJson.decodeFromString<CatalogFile>(text).feeds
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to load bundled catalog", t)
        emptyList()
    }

    private companion object {
        const val ASSET_NAME = "feeds.json"
        const val TAG = "FeedCatalog"
    }
}
