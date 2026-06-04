package dev.fahim.livescanner.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Local persistence for everything the user owns: favorites, custom feeds, and
 * (optional) Broadcastify Premium credentials.
 *
 * Credentials are stored in plain SharedPreferences. That's acceptable for a
 * sideloaded personal app; if you want them encrypted, swap in
 * androidx.security:security-crypto EncryptedSharedPreferences here.
 */
class UserPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("livescanner", Context.MODE_PRIVATE)

    fun loadFavorites(): Set<String> =
        prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()

    fun saveFavorites(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_FAVORITES, ids).apply()
    }

    fun loadCustomFeeds(): List<Feed> = try {
        prefs.getString(KEY_CUSTOM_FEEDS, null)
            ?.let { AppJson.decodeFromString<List<Feed>>(it) }
            ?: emptyList()
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to decode custom feeds", t)
        emptyList()
    }

    fun saveCustomFeeds(feeds: List<Feed>) {
        prefs.edit().putString(KEY_CUSTOM_FEEDS, AppJson.encodeToString(feeds)).apply()
    }

    var broadcastifyUsername: String?
        get() = prefs.getString(KEY_BCFY_USER, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_BCFY_USER, value).apply()

    var broadcastifyPassword: String?
        get() = prefs.getString(KEY_BCFY_PASS, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_BCFY_PASS, value).apply()

    var groqApiKey: String?
        get() = prefs.getString(KEY_GROQ, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_GROQ, value).apply()

    val hasBroadcastifyCredentials: Boolean
        get() = !broadcastifyUsername.isNullOrBlank() && !broadcastifyPassword.isNullOrBlank()

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_CUSTOM_FEEDS = "custom_feeds"
        const val KEY_BCFY_USER = "bcfy_user"
        const val KEY_BCFY_PASS = "bcfy_pass"
        const val KEY_GROQ = "groq_key"
        const val TAG = "UserPrefs"
    }
}
