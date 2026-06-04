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
import dev.fahim.livescanner.data.Feed
import dev.fahim.livescanner.data.LatLng
import dev.fahim.livescanner.playback.ScannerPlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaybackUiState(
    val isConnected: Boolean = false,
    val currentMediaId: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val errorMessage: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as LiveScannerApp).container
    val repository = container.repository
    private val locationProvider = container.locationProvider

    private var controller: MediaController? = null
    private var lastError: String? = null

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

    // Reactive state mirrored straight from the repository.
    val allFeeds = repository.allFeeds
    val favorites = repository.favorites
    val customFeeds = repository.customFeeds
    val broadcastifyConfigured = repository.broadcastifyConfigured

    private val _location = MutableStateFlow<LatLng?>(null)
    val location: StateFlow<LatLng?> = _location.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = pushState()

        override fun onPlayerError(error: PlaybackException) {
            lastError = error.localizedMessage ?: "Couldn't play this feed"
            pushState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lastError = null
        }
    }

    init {
        _location.value = locationProvider.lastKnownLocation()
        connectController()
    }

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
        _playback.value = PlaybackUiState(
            isConnected = true,
            currentMediaId = item?.mediaId,
            title = item?.mediaMetadata?.title?.toString(),
            subtitle = item?.mediaMetadata?.subtitle?.toString()
                ?: item?.mediaMetadata?.artist?.toString(),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            errorMessage = lastError,
        )
    }

    fun play(feed: Feed) {
        val c = controller ?: return
        lastError = null
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

    fun toggleFavorite(id: String) = repository.toggleFavorite(id)

    fun addCustomFeed(feed: Feed) = repository.addOrUpdateCustomFeed(feed)

    fun removeFeed(id: String) {
        if (playback.value.currentMediaId == id) stop()
        repository.removeCustomFeed(id)
    }

    fun saveBroadcastifyCredentials(user: String?, pass: String?) =
        repository.setBroadcastifyCredentials(user, pass)

    fun broadcastifyUsername(): String? = repository.broadcastifyUsername()

    fun broadcastifyPassword(): String? = repository.broadcastifyPassword()

    fun groqApiKey(): String? = repository.groqApiKey()

    fun saveGroqApiKey(key: String?) = repository.setGroqApiKey(key)

    fun setQuery(value: String) {
        _query.value = value
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
}
