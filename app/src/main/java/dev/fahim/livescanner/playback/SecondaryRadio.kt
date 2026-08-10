package dev.fahim.livescanner.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.fahim.livescanner.data.Feed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * COMM 2 — a second feed monitored underneath the primary one, the way a real radio stack lets you
 * keep an ear on ground while you work tower.
 *
 * Deliberately thinner than the main engine: no MediaSession, no lock-screen controls, no rolling
 * buffer and no transcription. It exists to be *heard*, quietly, behind COMM 1 — so it runs at a
 * fixed duck and takes no audio focus of its own, which would pause the primary.
 */
class SecondaryRadio(private val context: Context) {

    private var player: ExoPlayer? = null

    private val _feedId = MutableStateFlow<String?>(null)
    val feedId: StateFlow<String?> = _feedId.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("LiveScanner/0.1 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                // Taking focus here would pause COMM 1 — the whole point is to sit under it.
                /* handleAudioFocus = */ false,
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also {
                it.volume = DUCKED_VOLUME
                player = it
            }
    }

    /** Tunes COMM 2, or retunes it if a different feed is already monitored. */
    fun tune(feed: Feed) {
        val uri = StreamResolver.resolveUri(feed) ?: return
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.play()
        _feedId.value = feed.id
        _playing.value = true
    }

    fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    fun stop() {
        player?.run {
            stop()
            clearMediaItems()
        }
        _feedId.value = null
        _playing.value = false
    }

    fun release() {
        player?.release()
        player = null
        _feedId.value = null
        _playing.value = false
    }

    private companion object {
        /** COMM 2 sits well under COMM 1 so the primary stays intelligible. */
        const val DUCKED_VOLUME = 0.45f
    }
}
