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

    // ── Flight Deck panel state ──────────────────────────────────────────────────────────────

    /** Scope range in nautical miles: 10, 20 or 40. */
    var rangeNm: Int
        get() = prefs.getInt(KEY_RANGE_NM, 40)
        set(value) = prefs.edit().putInt(KEY_RANGE_NM, value).apply()

    /** CC — live captions. */
    var captionsOn: Boolean
        get() = prefs.getBoolean(KEY_CC, false)
        set(value) = prefs.edit().putBoolean(KEY_CC, value).apply()

    /** EN — plain-English paraphrase of every transcript surface. */
    var plainEnglishOn: Boolean
        get() = prefs.getBoolean(KEY_EN, false)
        set(value) = prefs.edit().putBoolean(KEY_EN, value).apply()

    /** FLW — spotlight the aircraft currently being talked to. */
    var followOn: Boolean
        get() = prefs.getBoolean(KEY_FLW, true)
        set(value) = prefs.edit().putBoolean(KEY_FLW, value).apply()

    var gain: Int
        get() = prefs.getInt(KEY_GAIN, 50)
        set(value) = prefs.edit().putInt(KEY_GAIN, value).apply()

    var squelch: Int
        get() = prefs.getInt(KEY_SQUELCH, 38)
        set(value) = prefs.edit().putInt(KEY_SQUELCH, value).apply()

    /** VOICE | FLAT | LOW-CUT | NARROW */
    var eqPreset: String
        get() = prefs.getString(KEY_EQ, "VOICE") ?: "VOICE"
        set(value) = prefs.edit().putString(KEY_EQ, value).apply()

    var noiseGate: Boolean
        get() = prefs.getBoolean(KEY_GATE, true)
        set(value) = prefs.edit().putBoolean(KEY_GATE, value).apply()

    var trimSilence: Boolean
        get() = prefs.getBoolean(KEY_TRIM, true)
        set(value) = prefs.edit().putBoolean(KEY_TRIM, value).apply()

    var duckForNav: Boolean
        get() = prefs.getBoolean(KEY_DUCK, false)
        set(value) = prefs.edit().putBoolean(KEY_DUCK, value).apply()

    var nightMode: Boolean
        get() = prefs.getBoolean(KEY_NIGHT, false)
        set(value) = prefs.edit().putBoolean(KEY_NIGHT, value).apply()

    fun loadRules(): List<AlertRule> = try {
        prefs.getString(KEY_RULES, null)
            ?.let { AppJson.decodeFromString<List<AlertRule>>(it) }
            ?: seedRules()
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to decode alert rules", t)
        seedRules()
    }

    fun saveRules(rules: List<AlertRule>) {
        prefs.edit().putString(KEY_RULES, AppJson.encodeToString(rules)).apply()
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_CUSTOM_FEEDS = "custom_feeds"
        const val KEY_BCFY_USER = "bcfy_user"
        const val KEY_BCFY_PASS = "bcfy_pass"
        const val KEY_GROQ = "groq_key"
        const val KEY_RANGE_NM = "range_nm"
        const val KEY_CC = "cc_on"
        const val KEY_EN = "en_on"
        const val KEY_FLW = "flw_on"
        const val KEY_GAIN = "gain"
        const val KEY_SQUELCH = "squelch"
        const val KEY_EQ = "eq_preset"
        const val KEY_GATE = "noise_gate"
        const val KEY_TRIM = "trim_silence"
        const val KEY_DUCK = "duck_for_nav"
        const val KEY_NIGHT = "night_mode"
        const val KEY_RULES = "alert_rules"
        const val TAG = "UserPrefs"
    }
}
