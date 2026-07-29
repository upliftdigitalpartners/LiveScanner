package dev.fahim.livescanner.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fahim.livescanner.ui.theme.FdDim
import dev.fahim.livescanner.ui.theme.FlightDeck
import dev.fahim.livescanner.ui.theme.LiveScannerTheme

/** The spec's screen-change curve: 450 ms on cubic-bezier(.4, 0, .2, 1). */
private val ScreenChange = tween<Float>(
    durationMillis = 450,
    easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            val audio by vm.audio.collectAsStateWithLifecycle()
            LiveScannerTheme(night = audio.night) {
                AppRoot(vm)
            }
        }
    }
}

/**
 * The whole app is one horizontal filmstrip — Home, Radar, History, Alerts, Audio — with the
 * priority alert banner floating above whichever page you are on.
 */
@Composable
fun AppRoot(vm: MainViewModel) {
    var showAddFeed by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val screen by vm.screen.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { Screen.entries.size })

    // Ask for notifications once on Android 13+ so the media controls notification can show.
    val notifLauncher = rememberLauncherForActivityResult(RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Model drives the pager; the pager reports back what the user swiped to.
    LaunchedEffect(screen) {
        if (pagerState.currentPage != screen.ordinal) {
            pagerState.animateScrollToPage(screen.ordinal, animationSpec = ScreenChange)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            Screen.entries.getOrNull(page)?.let { if (it != screen) vm.goTo(it) }
        }
    }

    BackHandler(enabled = screen != Screen.HOME) { vm.goTo(Screen.HOME) }

    Box(
        Modifier
            .fillMaxSize()
            .background(FlightDeck.bg),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (Screen.entries[page]) {
                Screen.HOME -> HomeScreen(
                    vm = vm,
                    onAddFeed = { showAddFeed = true },
                    onOpenSettings = { showSettings = true },
                )

                Screen.RADAR -> RadarScreen(vm = vm, onBack = { vm.goTo(Screen.HOME) })

                Screen.HISTORY -> Box(Modifier.statusBarsPadding()) {
                    HistoryScreen(vm = vm, onBack = { vm.goTo(Screen.HOME) })
                }

                Screen.ALERTS -> Box(Modifier.statusBarsPadding()) {
                    AlertsScreen(vm = vm, onBack = { vm.goTo(Screen.HOME) })
                }

                Screen.AUDIO -> Box(Modifier.statusBarsPadding()) {
                    AudioScreen(vm = vm, onBack = { vm.goTo(Screen.HOME) })
                }
            }
        }

        alerts.active?.let { alert ->
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = FdDim.gutter)
                    .padding(top = 13.dp),
            ) {
                AlertBanner(
                    title = alert.title,
                    body = alert.body,
                    onDismiss = vm::dismissAlert,
                )
            }
        }
    }

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
