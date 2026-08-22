package dev.fahim.livescanner.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.fahim.livescanner.data.LatLng
import dev.fahim.livescanner.data.MercatorTiles
import dev.fahim.livescanner.data.RainViewerClient
import kotlinx.coroutines.delay

/** One decoded radar tile, with the geographic box it belongs in. */
class WeatherTile(val tile: MercatorTiles.Tile, val image: ImageBitmap)

/** Radar imagery is published roughly every ten minutes; half that is a safe poll. */
private const val REFRESH_MS = 5 * 60 * 1000L

/**
 * Loads the precipitation tiles covering the scope and keeps them current.
 *
 * The tile set is recomputed on every composition — it is a handful of integer divisions — but the
 * loader is keyed on the resulting refs, so panning within a tile or resizing the scope costs
 * nothing. Only crossing a tile boundary or changing zoom triggers new downloads.
 */
@Composable
fun rememberWeatherTiles(
    origin: LatLng,
    rangeNm: Float,
    enabled: Boolean,
): List<WeatherTile> {
    var tiles by remember { mutableStateOf<List<WeatherTile>>(emptyList()) }

    val refs = remember(origin.lat, origin.lon, rangeNm) {
        if (!enabled) {
            emptyList()
        } else {
            MercatorTiles.covering(
                originLat = origin.lat,
                originLon = origin.lon,
                rangeNm = rangeNm,
                zoom = MercatorTiles.zoomFor(rangeNm),
            )
        }
    }

    LaunchedEffect(refs, enabled) {
        if (!enabled || refs.isEmpty()) {
            tiles = emptyList()
            return@LaunchedEffect
        }
        while (true) {
            val frame = RainViewerClient.latestFrame()
            if (frame != null) {
                val loaded = ArrayList<WeatherTile>(refs.size)
                for (ref in refs) {
                    val bytes = RainViewerClient.tile(frame, ref) ?: continue
                    val bitmap = runCatching {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull() ?: continue
                    loaded.add(WeatherTile(ref, bitmap.asImageBitmap()))
                }
                // Publish once, so the scope doesn't redraw per tile as they trickle in.
                tiles = loaded
            }
            delay(REFRESH_MS)
        }
    }

    return tiles
}
