package dev.fahim.livescanner

import android.app.Application
import dev.fahim.livescanner.data.FeedCatalog
import dev.fahim.livescanner.data.FeedRepository
import dev.fahim.livescanner.data.LocationProvider
import dev.fahim.livescanner.data.UserPrefs
import dev.fahim.livescanner.playback.AlertNotifier
import dev.fahim.livescanner.playback.AudioBuffer
import dev.fahim.livescanner.playback.FlightDeckDsp
import dev.fahim.livescanner.playback.ReplayPlayer

/** Application holding the manual dependency container. */
class LiveScannerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Hand-rolled DI: one repository instance shared by the UI and the playback service.
 *
 * The rolling buffer and the DSP chain live here for the same reason — the service writes to
 * them from the playback thread while the ViewModel reads them for the recorder and the audio
 * panel, and both need to be looking at the same objects.
 */
class AppContainer(app: Application) {
    val prefs = UserPrefs(app)
    private val catalog = FeedCatalog(app)
    val repository = FeedRepository(catalog, prefs)
    val locationProvider = LocationProvider(app)
    val audioBuffer = AudioBuffer(app)
    val dsp = FlightDeckDsp()
    val replayPlayer = ReplayPlayer(app)
    val notifier = AlertNotifier(app).apply { ensureChannel() }
}
