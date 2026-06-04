package dev.fahim.livescanner

import android.app.Application
import dev.fahim.livescanner.data.FeedCatalog
import dev.fahim.livescanner.data.FeedRepository
import dev.fahim.livescanner.data.LocationProvider
import dev.fahim.livescanner.data.UserPrefs

/** Application holding the manual dependency container. */
class LiveScannerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Hand-rolled DI: one repository instance shared by the UI and the playback service. */
class AppContainer(app: Application) {
    val prefs = UserPrefs(app)
    private val catalog = FeedCatalog(app)
    val repository = FeedRepository(catalog, prefs)
    val locationProvider = LocationProvider(app)
}
