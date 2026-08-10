package dev.fahim.livescanner.ui

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dev.fahim.livescanner.LiveScannerApp
import dev.fahim.livescanner.data.ActiveAlert
import dev.fahim.livescanner.data.Aircraft
import dev.fahim.livescanner.data.AlertRule
import dev.fahim.livescanner.data.Feed
import dev.fahim.livescanner.data.GroqTranscriber
import dev.fahim.livescanner.data.LatLng
import dev.fahim.livescanner.data.Priority
import dev.fahim.livescanner.data.RuleAccent
import dev.fahim.livescanner.data.RuleType
import dev.fahim.livescanner.data.Transmission
import dev.fahim.livescanner.data.AdsbClient
import dev.fahim.livescanner.data.normalizeFlightNumber
import dev.fahim.livescanner.data.priorityFor
import dev.fahim.livescanner.playback.EqPreset
import dev.fahim.livescanner.playback.ScannerPlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** The five pages of the horizontal filmstrip. */
enum class Screen { HOME, RADAR, HISTORY, ALERTS, AUDIO }

/** Soft keys along the bottom of the comm panel. */
enum class FeedTab(val label: String) { NRST("NRST"), ATC("ATC"), SCAN("SCAN"), FAV("FAV") }

data class PlaybackUiState(
    val isConnected: Boolean = false,
    val currentMediaId: String? = null,
    val feed: Feed? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val errorMessage: String? = null,
)

data class RadarUiState(
    val rangeNm: Int = 40,
    val aircraft: List<Aircraft> = emptyList(),
    val trails: Map<String, List<LatLng>> = emptyMap(),
    val selectedHex: String? = null,
    /** Aircraft that just entered range — draws the expanding ping, cleared after 3.3 s. */
    val newHex: String? = null,
    val captionsOn: Boolean = false,
    val plainEnglishOn: Boolean = false,
    val followOn: Boolean = true,
    val caption: String? = null,
    /** Callsigns in the transmission currently being decoded — magenta ring on the scope. */
    val transcribing: Set<String> = emptySet(),
    /** Callsigns named by an armed flight/tail rule — pinned on the scope whether or not
     *  anyone is talking to them. */
    val tracked: Set<String> = emptySet(),
)

data class HistoryUiState(
    val transmissions: List<Transmission> = emptyList(),
    val expandedId: String? = null,
    val replayPct: Int = 0,
    /** When set, the recorder shows only this aircraft's transmissions. */
    val filterCallsign: String? = null,
) {
    val visible: List<Transmission>
        get() = filterCallsign?.let { wanted ->
            transmissions.filter { it.callsign?.equals(wanted, ignoreCase = true) == true }
        } ?: transmissions

    /** Callsigns present in the log, for the recorder's filter row. */
    val callsigns: List<String>
        get() = transmissions.mapNotNull { it.callsign }.distinct().take(8)
}

data class AlertsUiState(
    val rules: List<AlertRule> = emptyList(),
    val ruleType: RuleType = RuleType.KEYWORD,
    val active: ActiveAlert? = null,
)

/**
 * Panel settings only. Signal level, gate state and night mode are deliberately *not* here:
 * level updates at audio-buffer rate, and anything that shares a state object with it recomposes
 * at that rate too — which is how the whole app ended up redrawing tens of times a second.
 */
data class AudioUiState(
    val gain: Int = 50,
    val squelch: Int = 38,
    val eq: EqPreset = EqPreset.VOICE,
    val noiseGate: Boolean = true,
    val trimSilence: Boolean = true,
    val duckForNav: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as LiveScannerApp).container
    val repository = container.repository
    private val locationProvider = container.locationProvider
    private val prefs = container.prefs
    private val audioBuffer = container.audioBuffer
    private val dsp = container.dsp
    private val notifier = container.notifier

    private var controller: MediaController? = null
    private var lastError: String? = null

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

    private val _radar = MutableStateFlow(
        RadarUiState(
            rangeNm = prefs.rangeNm,
            captionsOn = prefs.captionsOn,
            plainEnglishOn = prefs.plainEnglishOn,
            followOn = prefs.followOn,
        ),
    )
    val radar: StateFlow<RadarUiState> = _radar.asStateFlow()

    private val _history = MutableStateFlow(HistoryUiState())
    val history: StateFlow<HistoryUiState> = _history.asStateFlow()

    private val _alerts = MutableStateFlow(AlertsUiState(rules = prefs.loadRules()))
    val alerts: StateFlow<AlertsUiState> = _alerts.asStateFlow()

    private val _audio = MutableStateFlow(
        AudioUiState(
            gain = prefs.gain,
            squelch = prefs.squelch,
            eq = EqPreset.fromKey(prefs.eqPreset),
            noiseGate = prefs.noiseGate,
            trimSilence = prefs.trimSilence,
            duckForNav = prefs.duckForNav,
        ),
    )
    val audio: StateFlow<AudioUiState> = _audio.asStateFlow()

    /** Read only by the app shell, so a theme swap is the one thing that redraws everything. */
    private val _night = MutableStateFlow(prefs.nightMode)
    val night: StateFlow<Boolean> = _night.asStateFlow()

    /** Straight off the DSP, which rate-limits its own publishing. Read only by the audio scope. */
    val signalLevel: StateFlow<Float> = dsp.level
    val gateOpen: StateFlow<Boolean> = dsp.gateOpen

    private val _tab = MutableStateFlow(FeedTab.NRST)
    val tab: StateFlow<FeedTab> = _tab.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _location = MutableStateFlow<LatLng?>(null)
    val location: StateFlow<LatLng?> = _location.asStateFlow()

    /** UTC seconds ticker for the header clock. */
    private val _utcTick = MutableStateFlow(System.currentTimeMillis())
    val utcTick: StateFlow<Long> = _utcTick.asStateFlow()

    val allFeeds = repository.allFeeds
    val favorites = repository.favorites
    val customFeeds = repository.customFeeds
    val broadcastifyConfigured = repository.broadcastifyConfigured

    private var adsbJob: Job? = null
    private var transcribeJob: Job? = null
    private var alertJob: Job? = null
    private var pingJob: Job? = null
    private var knownHexes = emptySet<String>()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = pushState()

        override fun onPlayerError(error: PlaybackException) {
            lastError = when (error.errorCode) {
                // LiveATC/Broadcastify return 404 when a feed's source is offline.
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                -> "Feed offline — the source isn't broadcasting right now"
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                -> "No connection — check your internet"
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                -> "Stream problem — this feed may have moved"
                else -> error.localizedMessage ?: "Couldn't play this feed"
            }
            pushState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lastError = null
        }
    }

    init {
        _location.value = locationProvider.lastKnownLocation()
        connectController()
        pushDspSettings()
        publishTracked()

        viewModelScope.launch {
            while (true) {
                _utcTick.value = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }

    // ── Playback ─────────────────────────────────────────────────────────────────────────────

    private fun connectController() {
        val token = SessionToken(
            getApplication(),
            ComponentName(getApplication(), ScannerPlaybackService::class.java),
        )
        val future = MediaController.Builder(getApplication(), token).buildAsync()
        future.addListener({
            controller = future.get().also { c ->
                c.addListener(playerListener)
                pushState()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun pushState() {
        val c = controller
        if (c == null) {
            _playback.value = PlaybackUiState(isConnected = false)
            return
        }
        val item = c.currentMediaItem
        val feed = item?.mediaId?.let { repository.feedById(it) }
        _playback.value = PlaybackUiState(
            isConnected = true,
            currentMediaId = item?.mediaId,
            feed = feed,
            title = item?.mediaMetadata?.title?.toString(),
            subtitle = item?.mediaMetadata?.subtitle?.toString()
                ?: item?.mediaMetadata?.artist?.toString(),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            errorMessage = lastError,
        )
        restartLiveLoops()
    }

    fun play(feed: Feed) {
        val c = controller ?: return
        lastError = null
        // Each feed keeps its own rolling window, so tuning away and back doesn't lose it.
        audioBuffer.switchTo(feed.id)
        val item = MediaItem.Builder()
            .setMediaId(feed.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(feed.name)
                    .setSubtitle(feed.subtitle)
                    .setArtist(feed.location)
                    .build(),
            )
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
        lastError = null
        pushState()
    }

    // ── Navigation ───────────────────────────────────────────────────────────────────────────

    fun goTo(target: Screen) { _screen.value = target }

    /** Radar, history and alerts are written around an airport feed; scanner feeds have no scope. */
    fun radarAvailable(): Boolean = _playback.value.feed?.hasCoordinates == true

    // ── Feed list ────────────────────────────────────────────────────────────────────────────

    fun setTab(value: FeedTab) { _tab.value = value }

    fun setQuery(value: String) { _query.value = value }

    fun toggleFavorite(id: String) = repository.toggleFavorite(id)

    fun addCustomFeed(feed: Feed) = repository.addOrUpdateCustomFeed(feed)

    fun removeFeed(id: String) {
        if (playback.value.currentMediaId == id) stop()
        repository.removeCustomFeed(id)
    }

    // ── Radar ────────────────────────────────────────────────────────────────────────────────

    fun setRange(nm: Int) {
        prefs.rangeNm = nm
        _radar.update { it.copy(rangeNm = nm, selectedHex = null) }
        restartLiveLoops()
    }

    fun selectAircraft(hex: String?) { _radar.update { it.copy(selectedHex = hex) } }

    fun toggleCaptions() {
        val next = !_radar.value.captionsOn
        prefs.captionsOn = next
        _radar.update { it.copy(captionsOn = next) }
        restartLiveLoops()
    }

    fun togglePlainEnglish() {
        val next = !_radar.value.plainEnglishOn
        prefs.plainEnglishOn = next
        _radar.update { it.copy(plainEnglishOn = next) }
    }

    fun toggleFollow() {
        val next = !_radar.value.followOn
        prefs.followOn = next
        _radar.update { it.copy(followOn = next) }
    }

    /**
     * The inputs that actually decide what the live loops should be doing. Player events fire
     * constantly during streaming, and restarting the ADS-B poll on each one meant a 5-second
     * fetch was cancelled before it could ever finish — the scope looked frozen and the network
     * churned. Restart only when one of these changes.
     */
    private data class LiveKey(
        val feedId: String?,
        val playing: Boolean,
        val rangeNm: Int,
        val captions: Boolean,
        val hasGroqKey: Boolean,
    )

    private var liveKey: LiveKey? = null

    /** (Re)starts the ADS-B poll and transcription loops for the feed that is currently playing. */
    private fun restartLiveLoops() {
        val feed = _playback.value.feed
        val playing = _playback.value.isPlaying

        val key = LiveKey(
            feedId = feed?.id,
            playing = playing,
            rangeNm = _radar.value.rangeNm,
            captions = _radar.value.captionsOn,
            hasGroqKey = repository.groqApiKey() != null,
        )
        if (key == liveKey) return
        liveKey = key

        adsbJob?.cancel()
        if (feed?.lat == null || feed.lon == null) {
            _radar.update { it.copy(aircraft = emptyList(), trails = emptyMap(), selectedHex = null) }
        } else if (!playing) {
            // Paused means the scope holds its last picture rather than stepping to new fixes —
            // the frame clock is stopped too, so nothing on it moves. The feed is still tuned, so
            // the contacts stay on screen; they just stop refreshing until playback resumes.
        } else {
            val center = LatLng(feed.lat, feed.lon)
            val range = _radar.value.rangeNm
            adsbJob = viewModelScope.launch {
                while (true) {
                    val fresh = AdsbClient.fetchNear(center.lat, center.lon, range)
                    val trails = _radar.value.trails.toMutableMap()
                    for (ac in fresh) {
                        trails[ac.hex] = (trails[ac.hex].orEmpty() + LatLng(ac.lat, ac.lon)).takeLast(8)
                    }
                    val liveHexes = fresh.mapTo(HashSet()) { it.hex }
                    trails.keys.retainAll(liveHexes)

                    val entered = liveHexes - knownHexes
                    knownHexes = liveHexes
                    _radar.update { it.copy(aircraft = fresh, trails = trails) }
                    entered.firstOrNull()?.let { pingContact(it) }

                    delay(ADSB_POLL_MS)
                }
            }
        }

        transcribeJob?.cancel()
        val key = repository.groqApiKey()
        if (_radar.value.captionsOn && playing && key != null) {
            transcribeJob = viewModelScope.launch { transcribeLoop(key) }
        } else if (_radar.value.captionsOn && key == null) {
            _radar.update { it.copy(caption = "Add a Groq API key in Settings to enable live transcription.") }
        }
    }

    private fun pingContact(hex: String) {
        pingJob?.cancel()
        pingJob = viewModelScope.launch {
            _radar.update { it.copy(newHex = hex) }
            delay(PING_MS)
            _radar.update { if (it.newHex == hex) it.copy(newHex = null) else it }
        }
    }

    /**
     * Pulls the newest few seconds out of the rolling buffer, transcribes it, and turns each
     * result into a recorder entry — captions, alert matching and follow all hang off this.
     */
    private suspend fun transcribeLoop(apiKey: String) {
        _radar.update { it.copy(caption = "Listening…") }
        while (true) {
            val segment = audioBuffer.latest(SEGMENT_MS)
            if (segment == null || segment.bytes.size < MIN_CLIP_BYTES) {
                delay(SEGMENT_MS)
                continue
            }
            val text = GroqTranscriber.transcribe(segment.bytes, apiKey)?.trim()
            if (text.isNullOrBlank()) {
                delay(500)
                continue
            }

            val plain = if (_radar.value.plainEnglishOn) {
                GroqTranscriber.plainEnglish(text, apiKey)
            } else {
                null
            }
            val feed = _playback.value.feed
            val bps = audioBuffer.bytesPerSecond()
            val callsigns = GroqTranscriber.identifyCallsigns(
                text,
                _radar.value.aircraft.mapNotNull { it.callsign }.distinct(),
                apiKey,
            )
            val entry = Transmission(
                id = UUID.randomUUID().toString(),
                timestampMs = System.currentTimeMillis(),
                feedId = feed?.id.orEmpty(),
                feedLabel = feed?.let { "${it.displayCode} ${shortFacility(it.name)}" } ?: "—",
                durationMs = ((segment.bytes.size / bps) * 1000).toLong(),
                raw = text,
                plainEnglish = plain,
                callsign = callsigns.firstOrNull(),
                priority = priorityFor(text),
                bufferOffset = segment.offset,
                bufferLength = segment.bytes.size,
                waveform = waveformOf(segment.bytes),
            )

            _history.update { it.copy(transmissions = (listOf(entry) + it.transmissions).take(MAX_HISTORY)) }

            val display = plain ?: text
            val hits = callsigns.map { it.trim().uppercase() }.toSet()
            _radar.update { state ->
                state.copy(
                    caption = display,
                    transcribing = hits,
                    selectedHex = if (state.followOn && hits.isNotEmpty()) {
                        state.aircraft.firstOrNull { it.callsign?.trim()?.uppercase() in hits }?.hex
                            ?: state.selectedHex
                    } else {
                        state.selectedHex
                    },
                )
            }

            matchAlerts(entry)
        }
    }

    // ── Alerts ───────────────────────────────────────────────────────────────────────────────

    fun setRuleType(type: RuleType) { _alerts.update { it.copy(ruleType = type) } }

    fun toggleRule(id: String) {
        val next = _alerts.value.rules.map { if (it.id == id) it.copy(on = !it.on) else it }
        prefs.saveRules(next)
        _alerts.update { it.copy(rules = next) }
        publishTracked()
    }

    /** Mirrors the armed aircraft rules into radar state so the scope can pin them. */
    private fun publishTracked() {
        val tracked = _alerts.value.rules
            .filter { it.on && it.tracksAircraft }
            .flatMap { it.terms }
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        _radar.update { it.copy(tracked = tracked) }
    }

    fun armRule(text: String) {
        val typed = text.trim().uppercase()
        if (typed.isEmpty()) return
        val type = _alerts.value.ruleType

        // A flight is stored as one ICAO callsign however it was typed — "UA328", "United 328"
        // and "UAL328" all become UAL328, which is what the transcriber resolves to.
        val term = if (type == RuleType.FLIGHT) normalizeFlightNumber(typed) ?: typed else typed

        val rule = AlertRule(
            id = "user:" + UUID.randomUUID(),
            type = type,
            name = "${typeLabel(type)} · $term",
            detail = when (type) {
                RuleType.FLIGHT -> "Tracks this flight on every feed and pins it on the scope"
                RuleType.TAIL -> "Notify whenever this aircraft is addressed"
                else -> "All active feeds · sound + banner"
            },
            on = true,
            accent = when (type) {
                RuleType.KEYWORD -> RuleAccent.MAGENTA
                RuleType.FLIGHT -> RuleAccent.AMBER
                RuleType.TAIL -> RuleAccent.CYAN
                RuleType.FEED -> RuleAccent.GREEN
            },
            terms = listOf(term),
        )
        val next = _alerts.value.rules + rule
        prefs.saveRules(next)
        _alerts.update { it.copy(rules = next) }
        publishTracked()
    }

    fun removeRule(id: String) {
        val next = _alerts.value.rules.filterNot { it.id == id }
        prefs.saveRules(next)
        _alerts.update { it.copy(rules = next) }
        publishTracked()
    }

    /** Fires the banner and the notification together, so both paths can be verified at once. */
    fun testFire() {
        val title = "PRIORITY · MEDFLIGHT 1"
        val body = "KBOS TWR — air ambulance declared priority, patient on board"
        fireAlert(ActiveAlert(title = title, body = body))
        notifier.post(title, body)
    }

    fun dismissAlert() {
        alertJob?.cancel()
        _alerts.update { it.copy(active = null) }
    }

    private fun matchAlerts(entry: Transmission) {
        // The resolved callsign matters as much as the words: controllers say "United three
        // twenty eight", never "UAL328", so a flight rule can only fire on the resolution.
        val rule = _alerts.value.rules.firstOrNull { it.matches(entry.raw, entry.callsign) }
            ?: return
        val title = if (entry.priority == Priority.EMERGENCY) {
            "EMERGENCY · ${entry.callsign ?: rule.name}"
        } else {
            "PRIORITY · ${entry.callsign ?: rule.name}"
        }
        val body = "${entry.feedLabel} — ${entry.plainEnglish ?: entry.raw}"
        fireAlert(ActiveAlert(title = title, body = body, ruleId = rule.id))
        // Arming a rule for your own flight is only useful if it reaches you while you are
        // doing something else, so it goes to the shade as well as the in-app banner.
        notifier.post(title, body)
    }

    private fun fireAlert(alert: ActiveAlert) {
        alertJob?.cancel()
        alertJob = viewModelScope.launch {
            _alerts.update { it.copy(active = alert) }
            delay(ALERT_MS)
            _alerts.update { if (it.active == alert) it.copy(active = null) else it }
        }
    }

    // ── History ──────────────────────────────────────────────────────────────────────────────

    fun expandTransmission(id: String?) {
        _history.update { it.copy(expandedId = if (it.expandedId == id) null else id, replayPct = 0) }
    }

    /** Narrows the recorder to one aircraft; passing the active callsign again clears it. */
    fun filterHistory(callsign: String?) {
        _history.update {
            val next = if (it.filterCallsign.equals(callsign, ignoreCase = true)) null else callsign
            it.copy(filterCallsign = next, expandedId = null, replayPct = 0)
        }
    }

    fun replay(id: String) {
        val entry = _history.value.transmissions.firstOrNull { it.id == id } ?: return
        val offset = entry.bufferOffset ?: return
        val bytes = audioBuffer.segment(offset, entry.bufferLength) ?: return
        viewModelScope.launch {
            // Progress is driven off the clip's own duration; the clip plays through the
            // dedicated replay player in the service so the live feed is not interrupted.
            container.replayPlayer.play(bytes)
            val steps = 40
            for (step in 1..steps) {
                delay(entry.durationMs / steps)
                _history.update { if (it.expandedId == id) it.copy(replayPct = step * 100 / steps) else it }
            }
            _history.update { if (it.expandedId == id) it.copy(replayPct = 0) else it }
        }
    }

    /** Writes a transmission's audio to a shareable file and returns it, or null if it aged out. */
    fun exportClip(id: String): java.io.File? {
        val entry = _history.value.transmissions.firstOrNull { it.id == id } ?: return null
        val offset = entry.bufferOffset ?: return null
        val bytes = audioBuffer.segment(offset, entry.bufferLength) ?: return null
        return runCatching {
            val dir = java.io.File(getApplication<Application>().cacheDir, "clips").apply { mkdirs() }
            java.io.File(dir, "clip_${entry.timestampMs}.mp3").apply { writeBytes(bytes) }
        }.getOrNull()
    }

    // ── Audio panel ──────────────────────────────────────────────────────────────────────────

    fun setGain(value: Int) {
        prefs.gain = value
        _audio.update { it.copy(gain = value) }
        pushDspSettings()
    }

    fun setSquelch(value: Int) {
        prefs.squelch = value
        _audio.update { it.copy(squelch = value) }
        pushDspSettings()
    }

    fun setEq(preset: EqPreset) {
        prefs.eqPreset = preset.label
        _audio.update { it.copy(eq = preset) }
        pushDspSettings()
    }

    fun toggleNoiseGate() {
        val next = !_audio.value.noiseGate
        prefs.noiseGate = next
        _audio.update { it.copy(noiseGate = next) }
        pushDspSettings()
    }

    fun toggleTrimSilence() {
        val next = !_audio.value.trimSilence
        prefs.trimSilence = next
        _audio.update { it.copy(trimSilence = next) }
        pushDspSettings()
    }

    fun toggleDuckForNav() {
        val next = !_audio.value.duckForNav
        prefs.duckForNav = next
        _audio.update { it.copy(duckForNav = next) }
    }

    fun toggleNight() {
        val next = !_night.value
        prefs.nightMode = next
        _night.value = next
    }

    private fun pushDspSettings() {
        val state = _audio.value
        dsp.gain = state.gain
        dsp.squelch = state.squelch
        dsp.gateEnabled = state.noiseGate
        dsp.trimSilence = state.trimSilence
        dsp.preset = state.eq
    }

    // ── Settings passthrough ─────────────────────────────────────────────────────────────────

    fun saveBroadcastifyCredentials(user: String?, pass: String?) =
        repository.setBroadcastifyCredentials(user, pass)

    fun broadcastifyUsername(): String? = repository.broadcastifyUsername()

    fun broadcastifyPassword(): String? = repository.broadcastifyPassword()

    fun groqApiKey(): String? = repository.groqApiKey()

    fun saveGroqApiKey(key: String?) {
        repository.setGroqApiKey(key)
        restartLiveLoops()
    }

    fun hasLocationPermission(): Boolean = locationProvider.hasPermission()

    fun refreshLocation() {
        viewModelScope.launch {
            locationProvider.awaitLocation()?.let { _location.value = it }
        }
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
    }

    private companion object {
        const val ADSB_POLL_MS = 5_000L
        const val SEGMENT_MS = 8_000L
        const val PING_MS = 3_300L
        const val ALERT_MS = 6_000L
        const val MIN_CLIP_BYTES = 800
        const val MAX_HISTORY = 200
    }
}

private fun typeLabel(type: RuleType): String = when (type) {
    RuleType.KEYWORD -> "KEYWORD"
    RuleType.FLIGHT -> "FLIGHT"
    RuleType.TAIL -> "TAIL #"
    RuleType.FEED -> "FEED"
}

/** "Boston Logan Tower" → "TWR", so the recorder's feed tag stays short. */
private fun shortFacility(name: String): String {
    val upper = name.uppercase()
    return when {
        "GROUND" in upper -> "GND"
        "TOWER" in upper || upper.endsWith(" TWR") -> "TWR"
        "APPROACH" in upper -> "APP"
        "DEPARTURE" in upper -> "DEP"
        "CLEARANCE" in upper || "DELIVERY" in upper -> "DEL"
        else -> "ATC"
    }
}

/** 34 normalised bars sketched from the compressed clip — enough to read as a waveform. */
private fun waveformOf(bytes: ByteArray): List<Float> {
    if (bytes.isEmpty()) return List(34) { 0.3f }
    val buckets = 34
    val per = (bytes.size / buckets).coerceAtLeast(1)
    val raw = ArrayList<Float>(buckets)
    for (b in 0 until buckets) {
        val start = b * per
        val end = minOf(start + per, bytes.size)
        if (start >= end) { raw.add(0f); continue }
        var sum = 0.0
        for (i in start until end) {
            val v = (bytes[i].toInt() and 0xFF) - 128
            sum += (v * v).toDouble()
        }
        raw.add(kotlin.math.sqrt(sum / (end - start)).toFloat())
    }
    val max = raw.maxOrNull()?.takeIf { it > 0f } ?: 1f
    return raw.map { (it / max).coerceIn(0.08f, 1f) }
}
