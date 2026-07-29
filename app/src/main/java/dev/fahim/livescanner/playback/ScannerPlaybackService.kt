package dev.fahim.livescanner.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TeeDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.fahim.livescanner.LiveScannerApp
import dev.fahim.livescanner.data.FeedRepository
import dev.fahim.livescanner.ui.MainActivity

/**
 * The single playback engine. Extends MediaLibraryService so the same session powers:
 *   - the in-app phone UI (via a MediaController),
 *   - the system media notification / lock screen,
 *   - Android Auto (which browses [MediaItemTree] and plays by mediaId).
 */
class ScannerPlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var repository: FeedRepository
    private lateinit var tree: MediaItemTree
    private lateinit var audioBuffer: AudioBuffer

    override fun onCreate() {
        super.onCreate()
        val container = (application as LiveScannerApp).container
        repository = container.repository
        tree = MediaItemTree(repository, container.locationProvider)
        audioBuffer = container.audioBuffer

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("LiveScanner/0.1 (Android)")
            .setAllowCrossProtocolRedirects(true) // LiveATC 302-redirects to a node host
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)

        // Inject Broadcastify Premium auth only for broadcastify.com hosts, only when configured.
        val dataSourceFactory = ResolvingDataSource.Factory(httpFactory) { dataSpec ->
            val host = dataSpec.uri.host
            if (host != null && host.endsWith("broadcastify.com")) {
                val header = StreamResolver.broadcastifyAuthHeader(repository)
                if (header != null) {
                    dataSpec.withAdditionalHeaders(mapOf("Authorization" to header))
                } else {
                    dataSpec
                }
            } else {
                dataSpec
            }
        }

        // Tee every byte into the rolling buffer on its way to the decoder, so the recorder and
        // live transcription read the same stream the speaker does — one connection, not two.
        val teeFactory = DataSource.Factory {
            TeeDataSource(dataSourceFactory.createDataSource(), BufferSink(audioBuffer))
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        player = ExoPlayer.Builder(this, FlightDeckRenderersFactory(this, container.dsp))
            .setMediaSourceFactory(DefaultMediaSourceFactory(teeFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        session = MediaLibrarySession.Builder(this, player, LibrarySessionCallback())
            .setSessionActivity(contentPendingIntent())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the user swipes the app away while paused/stopped, tear the service down.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session.release()
        player.release()
        audioBuffer.close()
        super.onDestroy()
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Ask Android Auto for a grid so every browse target clears the 72dp touch minimum.
            val styled = LibraryParams.Builder()
                .setExtras(tree.contentStyleExtras())
                .setRecent(params?.isRecent ?: false)
                .setOffline(params?.isOffline ?: false)
                .setSuggested(params?.isSuggested ?: false)
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(tree.rootItem(), styled))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(tree.children(parentId), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = tree.itemById(mediaId)
            return if (item != null) {
                Futures.immediateFuture(LibraryResult.ofItem(item, null))
            } else {
                Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
        }

        // Browse search (the search box in Android Auto / Assistant).
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            val count = tree.searchItems(query).size
            session.notifySearchResultChanged(browser, query, count, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(tree.searchItems(query), params))

        // Called when a controller (the phone UI or Android Auto) asks to play items.
        // Incoming items may carry only a mediaId, so resolve each to a stream URI here.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapTo(ArrayList(mediaItems.size)) { tree.resolveForPlayback(it) }
            return Futures.immediateFuture(resolved)
        }
    }
}

/**
 * Puts the comm-radio DSP chain into ExoPlayer's audio path.
 *
 * This is the one place the app reaches into Media3 internals: [DefaultRenderersFactory] builds
 * the audio sink, and the only supported way to insert processors is to override that build.
 */
private class FlightDeckRenderersFactory(
    context: Context,
    private val dsp: FlightDeckDsp,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf(dsp))
        .build()
}
