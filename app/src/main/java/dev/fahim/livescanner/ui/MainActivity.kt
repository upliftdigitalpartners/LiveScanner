package dev.fahim.livescanner.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fahim.livescanner.data.Feed
import dev.fahim.livescanner.data.LatLng
import dev.fahim.livescanner.playback.StreamResolver
import dev.fahim.livescanner.ui.theme.LiveScannerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiveScannerTheme {
                val vm: MainViewModel = viewModel()
                AppRoot(vm)
            }
        }
    }
}

@Composable
fun AppRoot(vm: MainViewModel) {
    var showAddFeed by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var radarFor by remember { mutableStateOf<Feed?>(null) }

    // Ask for notifications once on Android 13+ so the media controls notification can show.
    val notifLauncher = rememberLauncherForActivityResult(RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val radar = radarFor
    if (radar != null && radar.lat != null && radar.lon != null) {
        RadarScreen(
            center = LatLng(radar.lat, radar.lon),
            title = radar.name,
            streamUrl = StreamResolver.resolveUri(radar),
            groqApiKey = vm.groqApiKey(),
            onBack = { radarFor = null },
        )
    } else {
        HomeScreen(
            vm = vm,
            onAddFeed = { showAddFeed = true },
            onOpenSettings = { showSettings = true },
            onOpenRadar = { radarFor = it },
        )

        if (showAddFeed) {
            AddFeedDialog(
                onDismiss = { showAddFeed = false },
                onAdd = { feed ->
                    vm.addCustomFeed(feed)
                    vm.play(feed)
                    showAddFeed = false
                },
            )
        }
        if (showSettings) {
            SettingsDialog(vm = vm, onDismiss = { showSettings = false })
        }
    }
}
