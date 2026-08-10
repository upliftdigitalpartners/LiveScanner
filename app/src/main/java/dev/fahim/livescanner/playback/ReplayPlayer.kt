package dev.fahim.livescanner.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Plays a clip carved out of the rolling buffer, for the recorder's REPLAY control.
 *
 * Deliberately separate from the ExoPlayer that owns the live feed: replaying a transmission should
 * not tear down the stream you are listening to. Position is published so the waveform and the
 * transcript can follow the actual audio rather than a timer that only approximates it.
 */
class ReplayPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /** The transmission currently loaded, so the recorder knows which card owns the playhead. */
    @Volatile
    var loadedId: String? = null
        private set

    fun play(id: String, bytes: ByteArray, startAtMs: Long = 0L) {
        stop()
        try {
            val file = File(context.cacheDir, "replay.mp3").apply { writeBytes(bytes) }
            loadedId = id
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    _playing.value = false
                    _positionMs.value = 0L
                }
                prepare()
                _durationMs.value = duration.coerceAtLeast(0).toLong()
                if (startAtMs > 0) seekTo(startAtMs.toInt())
                start()
            }
            _playing.value = true
        } catch (t: Throwable) {
            Log.w(TAG, "replay failed", t)
            stop()
        }
    }

    /** Jumps within the loaded clip — what tapping a word in the transcript does. */
    fun seekTo(ms: Long) {
        val p = player ?: return
        try {
            p.seekTo(ms.coerceAtLeast(0L).toInt())
            if (!p.isPlaying) {
                p.start()
                _playing.value = true
            }
            _positionMs.value = ms
        } catch (t: Throwable) {
            Log.w(TAG, "seek failed", t)
        }
    }

    /** Sampled by the UI while a clip is playing; cheap enough to poll a few times a second. */
    fun refreshPosition() {
        val p = player ?: return
        try {
            if (p.isPlaying) _positionMs.value = p.currentPosition.toLong()
        } catch (_: Throwable) {
            // The player was released underneath us; the next play() will reset state.
        }
    }

    fun stop() {
        runCatching { player?.release() }
        player = null
        loadedId = null
        _playing.value = false
        _positionMs.value = 0L
        _durationMs.value = 0L
    }

    private companion object {
        const val TAG = "ReplayPlayer"
    }
}
