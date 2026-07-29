package dev.fahim.livescanner.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * Plays a clip carved out of the rolling buffer, for the recorder's REPLAY control.
 *
 * Deliberately separate from the ExoPlayer that owns the live feed: replaying a transmission
 * should not tear down the stream you are listening to.
 */
class ReplayPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    fun play(bytes: ByteArray) {
        stop()
        try {
            val file = File(context.cacheDir, "replay.mp3").apply { writeBytes(bytes) }
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "replay failed", t)
            stop()
        }
    }

    fun stop() {
        runCatching { player?.release() }
        player = null
    }

    private companion object {
        const val TAG = "ReplayPlayer"
    }
}
