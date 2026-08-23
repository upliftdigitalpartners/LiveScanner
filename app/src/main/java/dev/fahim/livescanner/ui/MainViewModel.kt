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
import androidx.compose.ui.geometry.Offset
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
import dev.fahim.livescanner.data.TranscriptWord
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

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
    /** Continuous, not stepped — pinch zooms it anywhere between 5 and 80 NM. */
    val rangeNm: Float = 40f,
    /** Scope origin when the user has dragged away from the field; null means centred on it. */
    val centerOffsetNm: Offset = Offset.Zero,
    /** Rotate the picture so the tuned airport's active approach points up. */
    val trackUp: Boolean = false,
    val aircraft: List<Aircraft> = emptyList(),
    val trails: Map<String, List<LatLng>> = emptyMap(),
    val selectedHex: String? = null,
    /** Aircraft that just entered range — draws the expanding ping, cleared after 3.3 s. */
    val newHex: String? = null,
    val captionsOn: Boolean = false,
    val plainEnglishOn: Boolean = false,
    val followOn: Boolean = true,
    /** WX — precipitation radar drawn beneath the traffic. */
    val weatherOn: Boolean = false,
    val caption: String? = null,
    /** Callsigns in the transmission currently being decoded — magenta ring on the scope. */
    val transcribing: Set<String> = emptySet(),
    /** Callsigns named by an armed flight/tail rule — pinned on the scope whether or not
     *  anyone is talking to them. */
    val tracked: Set<String> = emptySet(),
    /** Hex of the aircraft to ripple from when a transmission lands, cleared after the animation. */
    val rippleHex: String? = null,
    /** Bearing traffic is arriving on, inferred from inbound descending contacts. Null until known. */
    val approachBearing: Float? = null,
) {
    /** Where the scope is looking: the field, offset by any drag the user has applied. */
    fun centerFor(field: LatLng): LatLng = if (centerOffsetNm == Offset.Zero) {
        field
    } else {
        LatLng(
            lat = field.lat + centerOffsetNm.y / 60.0,
            lon = field.lon + centerOffsetNm.x / (60.0 * cos(Math.toRadians(field.lat))),
        )
    }

    /** Degrees the whole picture is rotated by. Track-up puts the active approach at the top. */
    val rotationDeg: Float
        get() = if (trackUp) -(approachBearing ?: 0f) else 0f
}

data class HistoryUiState(
    val transmissions: List<Transmission> = emptyList(),
    val expandedId: String? = null,
    val replayPct: Int = 0,
    /** When set, the recorder shows only this aircraft's transmissions. */
    val filterCallsign: String? = null,
    /** Wall-clock span the rolling buffer currently covers, for the timeline scrubber. */
    val bufferSpan: LongRange? = null,
    /** Playhead inside the expanded clip, driven by the replay player rather than a timer. */
    val replayPositionMs: Long = 0L,
    val replayDurationMs: Long = 0L,
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

data class AskUiState(
    val question: String = "",
    val answer: String? = null,
    val thinking: Boolean = false,
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
    private val replayPlayer = container.replayPlayer
    private val secondaryRadio = container.secondaryRadio
    val coastline = container.coastline

    /** Which feed COMM 2 is monitoring, or null when the second radio is off. */
    val comm2FeedId: StateFlow<String?> = secondaryRadio.feedId

    private var controller: MediaController? = null
    private var lastError: String? = null

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

    private val _radar = MutableStateFlow(
        RadarUiState(
            rangeNm = prefs.rangeNm.toFloat(),
            captionsOn = prefs.captionsOn,
            plainEnglishOn = prefs.plainEnglishOn,
            followOn = prefs.followOn,
            weatherOn = prefs.weatherOn,
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
    private var replayJob: Job? = null
    private var knownHexes = emptySet<String>()

    /** Feeds already tried for the current tune, so failover can't loop between dead mounts. */
    private val triedFeedIds = LinkedHashSet<String>()
    private var allSourcesOffline = false

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = pushState()

        override fun onPlayerError(error: PlaybackException) {
            // A dead mount is the normal failure here, not an exception: LiveATC feeds are run by
            // volunteers and go offline for months. Try the airport's other feeds before saying so.
            if (error.errorCode in OFFLINE_ERRORS && attemptFailover()) return

            lastError = when (error.errorCode) {
                // LiveATC/Broadcastify return 404 when a feed's source is offline.
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                -> if (allSourcesOffline) {
                    "Every source for this airport is offline — nothing to fall back to"
                } else {
                    "Feed offline — the source isn't broadcasting right now"
                }
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
        triedFeedIds.clear()
        triedFeedIds.add(feed.id)
        allSourcesOffline = false
        playInternal(feed)
    }

    /**
     * Falls forward to another feed at the same airport when the tuned one is offline.
     *
     * Charlotte is the case this exists for: three separate LiveATC mounts, all dead, and the app
     * used to show a cryptic error on the first one rather than trying the other two and then
     * saying plainly that the field is dark.
     */
    private fun attemptFailover(): Boolean {
        val current = _playback.value.feed ?: return false
        val next = repository.allFeeds.value.firstOrNull {
            it.displayCode == current.displayCode && it.id !in triedFeedIds
        }
        if (next == null) {
            // Every source for this airport has been tried; now the error is worth reporting.
            allSourcesOffline = triedFeedIds.size > 1
            return false
        }
        triedFeedIds.add(next.id)
        playInternal(next)
        return true
    }

    private fun playInternal(feed: Feed) {
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

    fun setRange(nm: Float) {
        val clamped = nm.coerceIn(MIN_RANGE_NM, MAX_RANGE_NM)
        prefs.rangeNm = clamped.roundToInt()
        _radar.update { it.copy(rangeNm = clamped) }
        // The poll radius is bucketed, so pinching does not restart the ADS-B loop every frame.
        restartLiveLoops()
    }

    /** Pinch zoom: multiplies the current range rather than stepping between fixed rings. */
    fun zoomRange(factor: Float) = setRange(_radar.value.rangeNm / factor)

    /** Drag the scope origin, in nautical miles east (x) and north (y) of the field. */
    fun panScope(deltaNmEast: Float, deltaNmNorth: Float) {
        _radar.update {
            val next = Offset(
                it.centerOffsetNm.x + deltaNmEast,
                it.centerOffsetNm.y + deltaNmNorth,
            )
            // Don't let the field disappear entirely — cap the offset at two screens out.
            val limit = it.rangeNm * 2f
            it.copy(
                centerOffsetNm = Offset(
                    next.x.coerceIn(-limit, limit),
                    next.y.coerceIn(-limit, limit),
                ),
            )
        }
    }

    fun recenterScope() = _radar.update { it.copy(centerOffsetNm = Offset.Zero) }

    fun toggleTrackUp() = _radar.update { it.copy(trackUp = !it.trackUp) }

    fun selectAircraft(hex: String?) { _radar.update { it.copy(selectedHex = hex) } }

    /** Long-press on a contact arms a tracking rule for it without typing the callsign. */
    fun trackAircraft(hex: String) {
        val ac = _radar.value.aircraft.firstOrNull { it.hex == hex } ?: return
        val term = ac.callsign?.trim()?.uppercase()
            ?: ac.registration?.trim()?.uppercase()
            ?: return
        if (_alerts.value.rules.any { rule -> rule.terms.any { it.equals(term, true) } }) return
        setRuleType(if (term.startsWith("N")) RuleType.TAIL else RuleType.FLIGHT)
        armRule(term)
    }

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

    fun toggleWeather() {
        val next = !_radar.value.weatherOn
        prefs.weatherOn = next
        _radar.update { it.copy(weatherOn = next) }
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
            rangeNm = pollRadiusNm(),
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
            val radius = pollRadiusNm()
            adsbJob = viewModelScope.launch {
                while (true) {
                    val fresh = AdsbClient.fetchNear(center.lat, center.lon, radius)
                    val trails = _radar.value.trails.toMutableMap()
                    for (ac in fresh) {
                        trails[ac.hex] = (trails[ac.hex].orEmpty() + LatLng(ac.lat, ac.lon)).takeLast(8)
                    }
                    val liveHexes = fresh.mapTo(HashSet()) { it.hex }
                    trails.keys.retainAll(liveHexes)

                    val entered = liveHexes - knownHexes
                    knownHexes = liveHexes
                    _radar.update {
                        it.copy(
                            aircraft = fresh,
                            trails = trails,
                            approachBearing = inferApproachBearing(fresh) ?: it.approachBearing,
                        )
                    }
                    entered.firstOrNull()?.let { pingContact(it) }

                    delay(ADSB_POLL_MS)
                }
            }
        }

        transcribeJob?.cancel()
        val groqKey = repository.groqApiKey()
        if (_radar.value.captionsOn && playing && groqKey != null) {
            transcribeJob = viewModelScope.launch { transcribeLoop(groqKey) }
        } else if (_radar.value.captionsOn && groqKey == null) {
            _radar.update { it.copy(caption = "Add a Groq API key in Settings to enable live transcription.") }
        }
    }

    /**
     * Poll radius, bucketed to 10 NM steps. Pinch zoom changes the range continuously, and the
     * poll must not restart on every frame of a gesture — nor refetch when a small zoom needs no
     * new data. Always fetches a little wider than the view so targets exist before they enter it.
     */
    private fun pollRadiusNm(): Int {
        val wanted = _radar.value.rangeNm * 1.3f
        return (kotlin.math.ceil(wanted / 10f).toInt() * 10).coerceIn(10, 120)
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
            val feed = _playback.value.feed
            // Whisper is primed with the tuned airport's own runway and fix names, which is the
            // difference between "cleared to SCUPP" and a line of nonsense.
            val detailed = GroqTranscriber.transcribeDetailed(
                segment.bytes,
                apiKey,
                airportIcao = feed?.displayCode,
            )
            if (detailed == null || detailed.text.isBlank()) {
                delay(500)
                continue
            }
            val text = detailed.text.trim()

            val plain = if (_radar.value.plainEnglishOn) {
                GroqTranscriber.plainEnglish(text, apiKey)
            } else {
                null
            }
            val bps = audioBuffer.bytesPerSecond()
            val callsigns = GroqTranscriber.identifyCallsigns(
                text,
                _radar.value.aircraft.mapNotNull { it.callsign }.distinct(),
                apiKey,
            )
            // Keyword rules only catch what you thought to ask for; the anomaly pass is what
            // surfaces the interesting transmission you had no rule for.
            val keywordPriority = priorityFor(text)
            val anomaly = if (keywordPriority == Priority.ROUTINE) {
                GroqTranscriber.anomalyScore(text, apiKey)
            } else {
                null
            }
            val priority = when {
                keywordPriority != Priority.ROUTINE -> keywordPriority
                (anomaly?.score ?: 0f) >= ANOMALY_NOTABLE -> Priority.NOTABLE
                else -> Priority.ROUTINE
            }

            val entry = Transmission(
                id = UUID.randomUUID().toString(),
                timestampMs = System.currentTimeMillis(),
                feedId = feed?.id.orEmpty(),
                feedLabel = feed?.let { "${it.displayCode} ${shortFacility(it.name)}" } ?: "—",
                durationMs = ((segment.bytes.size / bps) * 1000).toLong(),
                raw = text,
                plainEnglish = plain ?: anomaly?.takeIf { it.score >= ANOMALY_NOTABLE }?.reason,
                callsign = callsigns.firstOrNull(),
                priority = priority,
                bufferOffset = segment.offset,
                bufferLength = segment.bytes.size,
                waveform = waveformOf(segment.bytes),
                words = detailed.words.map { TranscriptWord(it.text, it.startMs, it.endMs) },
            )

            _history.update { it.copy(transmissions = (listOf(entry) + it.transmissions).take(MAX_HISTORY)) }

            val display = plain ?: text
            val hits = callsigns.map { it.trim().uppercase() }.toSet()
            val spokenTo = _radar.value.aircraft
                .firstOrNull { it.callsign?.trim()?.uppercase() in hits }?.hex
            _radar.update { state ->
                state.copy(
                    caption = display,
                    transcribing = hits,
                    selectedHex = if (state.followOn && spokenTo != null) spokenTo else state.selectedHex,
                )
            }
            // Ripple out from whoever was just addressed, so the sound has a place on the scope.
            spokenTo?.let { hex ->
                viewModelScope.launch {
                    _radar.update { it.copy(rippleHex = hex) }
                    delay(RIPPLE_MS)
                    _radar.update { if (it.rippleHex == hex) it.copy(rippleHex = null) else it }
                }
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

    /** Plays a recorded transmission, optionally from a word offset inside it. */
    fun replay(id: String, fromMs: Long = 0L) {
        val entry = _history.value.transmissions.firstOrNull { it.id == id } ?: return
        val offset = entry.bufferOffset ?: return
        val bytes = audioBuffer.segment(offset, entry.bufferLength) ?: return
        replayPlayer.play(id, bytes, fromMs)
        startReplayTracking()
    }

    /** Tapping a word in the transcript jumps the audio to that moment. */
    fun replayFromWord(id: String, word: TranscriptWord) = replay(id, word.startMs)

    /**
     * Follows the replay player's real position rather than estimating it. A timer only ever
     * approximates the audio, which shows up as a progress bar that drifts off the waveform.
     */
    private fun startReplayTracking() {
        replayJob?.cancel()
        replayJob = viewModelScope.launch {
            while (replayPlayer.playing.value) {
                replayPlayer.refreshPosition()
                val position = replayPlayer.positionMs.value
                val duration = replayPlayer.durationMs.value
                _history.update {
                    it.copy(
                        replayPositionMs = position,
                        replayDurationMs = duration,
                        replayPct = if (duration > 0) (position * 100 / duration).toInt() else 0,
                    )
                }
                delay(REPLAY_TICK_MS)
            }
            _history.update { it.copy(replayPct = 0, replayPositionMs = 0L) }
        }
    }

    /** Scrubs the whole 30-minute window: plays a slice starting at a wall-clock instant. */
    fun scrubBuffer(timeMs: Long) {
        val offset = audioBuffer.offsetAtTime(timeMs) ?: return
        val length = (audioBuffer.bytesPerSecond() * SCRUB_SECONDS).toInt()
        val bytes = audioBuffer.segment(offset, length) ?: return
        replayPlayer.play("buffer:$timeMs", bytes)
        startReplayTracking()
    }

    fun refreshBufferSpan() {
        _history.update { it.copy(bufferSpan = audioBuffer.timeSpan()) }
    }

    // ── Ask the feed ─────────────────────────────────────────────────────────────────────────

    private val _ask = MutableStateFlow(AskUiState())
    val ask: StateFlow<AskUiState> = _ask.asStateFlow()

    fun setAskQuestion(text: String) = _ask.update { it.copy(question = text) }

    fun clearAsk() {
        _ask.value = AskUiState()
    }

    /** Answers a question from the recorder log — nothing more, so it can't invent traffic. */
    fun askTheFeed() {
        val question = _ask.value.question.trim()
        if (question.isEmpty() || _ask.value.thinking) return
        val key = repository.groqApiKey() ?: run {
            _ask.update { it.copy(answer = "Add a Groq API key in Settings to ask about the feed.") }
            return
        }
        val log = _history.value.transmissions.map { "${it.clockLabel} ${it.raw}" }
        if (log.isEmpty()) {
            _ask.update { it.copy(answer = "Nothing recorded yet — turn captions on and let the feed run.") }
            return
        }
        viewModelScope.launch {
            _ask.update { it.copy(thinking = true, answer = null) }
            val answer = GroqTranscriber.askAboutTranscript(question, log, key)
            _ask.update {
                it.copy(thinking = false, answer = answer ?: "Couldn't reach Groq for that one.")
            }
        }
    }

    // ── COMM 2 ───────────────────────────────────────────────────────────────────────────────

    /** The second radio: a feed monitored quietly under the primary, as a real stack would. */
    fun tuneComm2(feed: Feed) {
        if (feed.id == _playback.value.currentMediaId) return // already on COMM 1
        secondaryRadio.tune(feed)
    }

    fun stopComm2() = secondaryRadio.stop()

    fun toggleComm2(feed: Feed) {
        if (secondaryRadio.feedId.value == feed.id) stopComm2() else tuneComm2(feed)
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
        replayPlayer.stop()
        secondaryRadio.release()
    }

    private companion object {
        const val MIN_RANGE_NM = 5f
        const val MAX_RANGE_NM = 80f
        const val RIPPLE_MS = 1_600L
        const val REPLAY_TICK_MS = 80L
        const val SCRUB_SECONDS = 12
        const val ADSB_POLL_MS = 5_000L
        const val SEGMENT_MS = 8_000L
        const val PING_MS = 3_300L
        const val ALERT_MS = 6_000L
        const val MIN_CLIP_BYTES = 800
        const val MAX_HISTORY = 200
        const val ANOMALY_NOTABLE = 0.55f

        /** Errors that mean "this mount is dead", as opposed to "your network is dead". */
        val OFFLINE_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        )
    }
}

private fun typeLabel(type: RuleType): String = when (type) {
    RuleType.KEYWORD -> "KEYWORD"
    RuleType.FLIGHT -> "FLIGHT"
    RuleType.TAIL -> "TAIL #"
    RuleType.FEED -> "FEED"
}

/**
 * The bearing traffic is arriving on, inferred from the traffic itself.
 *
 * Precise runway data isn't bundled, but aircraft on final announce the active runway by flying it:
 * low, slowing, descending, and all pointing the same way. The circular mean of their tracks is the
 * approach heading, which is what the corridor overlay and track-up mode need.
 */
private fun inferApproachBearing(aircraft: List<Aircraft>): Float? {
    val onApproach = aircraft.filter { ac ->
        val alt = ac.altitudeFt ?: return@filter false
        alt in 500..7_000 &&
            (ac.verticalRateFpm ?: 0) < -200 &&
            ac.groundSpeedKt in 110.0..260.0
    }
    if (onApproach.size < 2) return null

    var sumSin = 0.0
    var sumCos = 0.0
    for (ac in onApproach) {
        val rad = Math.toRadians(ac.trackDeg)
        sumSin += sin(rad)
        sumCos += cos(rad)
    }
    // A wide spread means arrivals aren't aligned — no single corridor to draw.
    val spread = hypot(sumSin, sumCos) / onApproach.size
    if (spread < 0.75) return null

    val mean = Math.toDegrees(atan2(sumSin, sumCos))
    return ((mean + 360.0) % 360.0).toFloat()
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
