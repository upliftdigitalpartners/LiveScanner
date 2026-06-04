package dev.fahim.livescanner.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Single source of truth for feeds and user state. Shared by both the phone UI and
 * the playback service so the browse tree Android Auto sees always matches the app.
 */
class FeedRepository(
    catalog: FeedCatalog,
    private val prefs: UserPrefs,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val bundled: List<Feed> = catalog.load()

    private val _customFeeds = MutableStateFlow(prefs.loadCustomFeeds())
    val customFeeds: StateFlow<List<Feed>> = _customFeeds.asStateFlow()

    private val _favorites = MutableStateFlow(prefs.loadFavorites())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _broadcastifyConfigured = MutableStateFlow(prefs.hasBroadcastifyCredentials)
    val broadcastifyConfigured: StateFlow<Boolean> = _broadcastifyConfigured.asStateFlow()

    private val _groqConfigured = MutableStateFlow(!prefs.groqApiKey.isNullOrBlank())
    val groqConfigured: StateFlow<Boolean> = _groqConfigured.asStateFlow()

    /** Bundled feeds plus user-added ones (a custom feed overrides a bundled one on id collision). */
    val allFeeds: StateFlow<List<Feed>> = _customFeeds
        .map { custom -> merge(bundled, custom) }
        .stateIn(scope, SharingStarted.Eagerly, merge(bundled, _customFeeds.value))

    private fun merge(base: List<Feed>, custom: List<Feed>): List<Feed> {
        val byId = LinkedHashMap<String, Feed>()
        base.forEach { byId[it.id] = it }
        custom.forEach { byId[it.id] = it }
        return byId.values.toList()
    }

    fun feedById(id: String): Feed? = allFeeds.value.firstOrNull { it.id == id }

    fun feedsByType(type: FeedType): List<Feed> =
        allFeeds.value.filter { it.type == type }.sortedBy { it.name }

    fun favoriteFeeds(): List<Feed> {
        val favs = _favorites.value
        return allFeeds.value.filter { it.id in favs }.sortedBy { it.name }
    }

    /** Feeds with coordinates, nearest first. Falls back to alphabetical when no location is known. */
    fun nearbyFeeds(from: LatLng?, limit: Int = 50): List<Feed> {
        if (from == null) return allFeeds.value.sortedBy { it.name }.take(limit)
        return allFeeds.value
            .mapNotNull { feed -> feed.distanceKmFrom(from)?.let { feed to it } }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
    }

    fun search(query: String): List<Feed> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return allFeeds.value.filter { feed ->
            feed.name.contains(q, ignoreCase = true) ||
                feed.location?.contains(q, ignoreCase = true) == true ||
                feed.description?.contains(q, ignoreCase = true) == true
        }.sortedBy { it.name }
    }

    fun isFavorite(id: String): Boolean = id in _favorites.value

    fun toggleFavorite(id: String) {
        val next = _favorites.value.toMutableSet()
        if (!next.add(id)) next.remove(id)
        _favorites.value = next
        prefs.saveFavorites(next)
    }

    fun addOrUpdateCustomFeed(feed: Feed) {
        _customFeeds.value = _customFeeds.value.filterNot { it.id == feed.id } + feed
        prefs.saveCustomFeeds(_customFeeds.value)
    }

    fun removeCustomFeed(id: String) {
        _customFeeds.value = _customFeeds.value.filterNot { it.id == id }
        prefs.saveCustomFeeds(_customFeeds.value)
        if (isFavorite(id)) toggleFavorite(id)
    }

    fun setBroadcastifyCredentials(user: String?, pass: String?) {
        prefs.broadcastifyUsername = user
        prefs.broadcastifyPassword = pass
        _broadcastifyConfigured.value = prefs.hasBroadcastifyCredentials
    }

    fun broadcastifyUsername(): String? = prefs.broadcastifyUsername
    fun broadcastifyPassword(): String? = prefs.broadcastifyPassword

    fun groqApiKey(): String? = prefs.groqApiKey

    fun setGroqApiKey(key: String?) {
        prefs.groqApiKey = key
        _groqConfigured.value = !prefs.groqApiKey.isNullOrBlank()
    }
}
