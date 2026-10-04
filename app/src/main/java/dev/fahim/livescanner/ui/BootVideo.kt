package dev.fahim.livescanner.ui

import android.content.ContentResolver
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.fahim.livescanner.R

/**
 * The CRT power-on clip behind the boot sequence.
 *
 * Deliberately does not replace what the boot screen draws. The vector rings and sweep keep
 * playing underneath, and this fades in over them only once [Player.Listener.onRenderedFirstFrame]
 * says there are actually pixels to show. That ordering is the whole point: a decoder that is slow,
 * a codec that is missing, or a device that refuses the file leaves the boot screen exactly as it
 * was rather than showing two and a half seconds of black.
 *
 * Muted and one-shot — the app's whole audio path is the radio, and a boot jingle would fight it.
 */
@Composable
fun BootVideo(onFirstFrame: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // The effect is keyed on the player, which never changes, so the callback is read through
    // this rather than captured once at first composition.
    val latestOnFirstFrame by rememberUpdatedState(onFirstFrame)

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            // The plain android.resource:// form rather than Media3's helper, which is
            // deprecated. Built from the running package name, not a literal, because the debug
            // build carries an applicationIdSuffix and a hardcoded id would miss it.
            setMediaItem(
                MediaItem.fromUri(
                    "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.boot_scope}",
                ),
            )
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() = latestOnFirstFrame()
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx -> SurfaceView(ctx).also(player::setVideoSurfaceView) },
    )
}
