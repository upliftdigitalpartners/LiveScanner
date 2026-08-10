package dev.fahim.livescanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fahim.livescanner.data.Feed
import dev.fahim.livescanner.data.FeedRepository
import dev.fahim.livescanner.data.FeedType
import dev.fahim.livescanner.data.LatLng
import dev.fahim.livescanner.data.distanceNmFrom
import dev.fahim.livescanner.ui.theme.B612Mono
import dev.fahim.livescanner.ui.theme.FdDim
import dev.fahim.livescanner.ui.theme.FdTracking
import dev.fahim.livescanner.ui.theme.FdType
import dev.fahim.livescanner.ui.theme.FlightDeck
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val UTC_CLOCK: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)

/**
 * The comm panel: what is playing, what else you could tune, and the way through to every other
 * surface. Everything above the feed list is the radio stack; everything below is the band.
 */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onAddFeed: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val p = FlightDeck
    val allFeeds by vm.allFeeds.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()

    val requestLocation = rememberLocationPermissionLauncher { granted ->
        if (granted) vm.refreshLocation()
    }

    val feeds = remember(tab, query, location, allFeeds, favorites, playback.currentMediaId) {
        feedsFor(vm.repository, tab, query, location)
            .filterNot { it.id == playback.currentMediaId }
    }
    val armedRules = alerts.rules.count { it.on }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .statusBarsPadding(),
    ) {
        // The clock flow is collected inside Header, not here — reading it at this level
        // recomposed the whole comm panel, feed list included, once a second.
        Header(vm.utcTick)

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = FdDim.gutter),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FdKey("HIST", false, FdAccent.NEUTRAL, Modifier.weight(1f)) { vm.goTo(Screen.HISTORY) }
            FdKey(
                "ALRT",
                active = armedRules > 0,
                accent = FdAccent.AMBER,
                modifier = Modifier.weight(1f),
            ) { vm.goTo(Screen.ALERTS) }
            FdKey("AUDIO", false, FdAccent.NEUTRAL, Modifier.weight(1f)) { vm.goTo(Screen.AUDIO) }
            FdKey("SET", false, FdAccent.NEUTRAL, Modifier.weight(1f), onClick = onOpenSettings)
        }

        Spacer(Modifier.height(12.dp))
        RadioStack(
            playback = playback,
            distanceNm = playback.feed?.distanceNmFrom(location),
            radarEnabled = vm.radarAvailable(),
            onRadar = { vm.goTo(Screen.RADAR) },
            onToggle = vm::togglePlayPause,
        )

        Spacer(Modifier.height(12.dp))
        SearchField(query, vm::setQuery)

        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = FdDim.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(
                if (query.isNotBlank()) "SEARCH RESULTS" else "STANDBY FEEDS · ${tabSubtitle(tab)}",
                Modifier.weight(1f),
            )
            SectionLabel("SIG")
        }

        // NRST is meaningless without a fix, so offer the grant right where the sort is promised.
        if (tab == FeedTab.NRST && query.isBlank() && !vm.hasLocationPermission()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FdDim.gutter, vertical = 6.dp)
                    .clip(RoundedCornerShape(FdDim.radiusRow))
                    .background(p.panelAlt)
                    .border(1.dp, p.strokeDim, RoundedCornerShape(FdDim.radiusRow))
                    .clickable { requestLocation() }
                    .padding(FdDim.rowPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelText(
                    "ALLOW LOCATION TO SORT BY DISTANCE",
                    Modifier.weight(1f),
                    color = p.textFaint,
                    size = FdType.control,
                )
                PanelText("ENABLE", color = p.cyan, size = FdType.control, bold = true)
            }
        }

        Spacer(Modifier.height(6.dp))
        if (feeds.isEmpty()) {
            EmptyState(tab, query.isNotBlank())
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = FdDim.gutter,
                    vertical = 4.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = feeds, key = { it.id }) { feed ->
                    FeedRow(
                        feed = feed,
                        isFavorite = feed.id in favorites,
                        distanceNm = feed.distanceNmFrom(location),
                        onPlay = { vm.play(feed) },
                        onToggleFavorite = { vm.toggleFavorite(feed.id) },
                    )
                }
            }
        }

        SoftKeys(
            tab = tab,
            onTab = vm::setTab,
            onAdd = onAddFeed,
        )
    }
}

@Composable
private fun Header(clock: kotlinx.coroutines.flow.StateFlow<Long>) {
    val p = FlightDeck
    val utcMillis by clock.collectAsStateWithLifecycle()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = FdDim.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "LIVE SCANNER",
            fontFamily = B612Mono,
            fontSize = FdType.wordmark,
            fontWeight = FontWeight.Bold,
            letterSpacing = FdTracking.wordmark,
            color = p.textHi,
        )
        Spacer(Modifier.width(10.dp))
        SectionLabel("COMM PANEL", Modifier.weight(1f))
        Text(
            "UTC ${UTC_CLOCK.format(Instant.ofEpochMilli(utcMillis))}",
            fontFamily = B612Mono,
            fontSize = FdType.control,
            letterSpacing = FdTracking.control,
            color = p.cyan,
        )
    }
}

/** The active radio stack: the one feed you are on, and the two things you can do to it. */
@Composable
private fun RadioStack(
    playback: PlaybackUiState,
    distanceNm: Double?,
    radarEnabled: Boolean,
    onRadar: () -> Unit,
    onToggle: () -> Unit,
) {
    val p = FlightDeck
    val feed = playback.feed
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = FdDim.gutter)
            .clip(RoundedCornerShape(FdDim.radiusCard))
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1219), Color(0xFF080E14))))
            .border(1.dp, p.stroke, RoundedCornerShape(FdDim.radiusCard))
            .padding(FdDim.cardPaddingTight),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("ACTIVE · COMM 1", Modifier.weight(1f))
            StatusDot(live = playback.isPlaying)
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    playback.errorMessage != null -> "NO SIGNAL"
                    playback.isBuffering -> "ACQUIRING"
                    playback.isPlaying -> "RX LIVE"
                    else -> "STANDBY"
                },
                fontFamily = B612Mono,
                fontSize = FdType.control,
                letterSpacing = FdTracking.control,
                color = if (playback.isPlaying) p.green else p.amber,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                feed?.let { "${it.displayCode} ${facilityWord(it.name)}" } ?: "— — —",
                modifier = Modifier.weight(1f),
                fontFamily = B612Mono,
                fontSize = FdType.ident,
                fontWeight = FontWeight.Bold,
                letterSpacing = FdTracking.ident,
                color = p.amber,
                maxLines = 1,
            )
            feed?.frequency?.let { freq ->
                Text(
                    freq,
                    fontFamily = B612Mono,
                    fontSize = FdType.frequency,
                    fontWeight = FontWeight.Bold,
                    color = p.amber,
                    maxLines = 1,
                )
            }
        }

        PanelText(
            buildString {
                append(feed?.name?.uppercase() ?: "NOTHING TUNED")
                if (distanceNm != null) append(" · ${formatNm(distanceNm)}")
            },
            color = p.textFaint,
            size = FdType.control,
            maxLines = 1,
        )

        playback.errorMessage?.let {
            Spacer(Modifier.height(4.dp))
            PanelText(it.uppercase(), color = p.red, size = FdType.control, maxLines = 2)
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            VuBars(playing = playback.isPlaying, modifier = Modifier.weight(1f))
            FdKey(
                "RADAR ▸",
                active = true,
                accent = FdAccent.GREEN,
                enabled = radarEnabled,
                onClick = onRadar,
            )
            Spacer(Modifier.width(8.dp))
            FdKey(
                if (playback.isPlaying) "❚❚ PAUSE" else "▶ RESUME",
                active = false,
                accent = FdAccent.NEUTRAL,
                enabled = playback.currentMediaId != null,
                onClick = onToggle,
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val p = FlightDeck
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = FdDim.gutter)
            .clip(RoundedCornerShape(FdDim.radiusRow))
            .background(p.panelAlt)
            .border(1.dp, p.strokeInput, RoundedCornerShape(FdDim.radiusRow))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "⌕",
                fontFamily = B612Mono,
                fontSize = FdType.body,
                color = p.textGhost,
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "SEARCH IDENT / CITY / FREQ…",
                        fontFamily = B612Mono,
                        fontSize = FdType.control,
                        letterSpacing = FdTracking.control,
                        color = p.textGhost,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        fontFamily = B612Mono,
                        fontSize = FdType.control,
                        letterSpacing = FdTracking.control,
                        color = p.textHi,
                    ),
                    cursorBrush = SolidColor(p.cyan),
                )
            }
        }
    }
}

@Composable
private fun FeedRow(
    feed: Feed,
    isFavorite: Boolean,
    distanceNm: Double?,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val p = FlightDeck
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FdDim.radiusRow))
            .background(p.panel)
            .border(1.dp, p.strokeDim, RoundedCornerShape(FdDim.radiusRow))
            .clickable(onClick = onPlay)
            .padding(FdDim.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CodeChip(
            feed.displayCode,
            if (feed.type == FeedType.ATC) FdAccent.CYAN else FdAccent.MAGENTA,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            PanelText(
                feed.name.uppercase(),
                color = p.textHi,
                size = FdType.rowTitle,
                maxLines = 1,
            )
            PanelText(
                buildString {
                    feed.location?.let { append(it.uppercase()) }
                    if (distanceNm != null) {
                        if (isNotEmpty()) append(" · ")
                        append(formatNm(distanceNm))
                    }
                    feed.frequency?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it)
                    }
                },
                color = p.textFaint,
                size = FdType.control,
                maxLines = 1,
            )
        }
        SignalStaircase(signalFor(distanceNm))
        Spacer(Modifier.width(10.dp))
        Text(
            if (isFavorite) "★" else "☆",
            modifier = Modifier
                .clickable(onClick = onToggleFavorite)
                .padding(4.dp),
            fontFamily = B612Mono,
            fontSize = FdType.rowTitle,
            color = if (isFavorite) p.amber else p.textGhost,
        )
    }
}

@Composable
private fun SoftKeys(tab: FeedTab, onTab: (FeedTab) -> Unit, onAdd: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = FdDim.gutter, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FeedTab.entries.forEach { entry ->
            FdKey(
                entry.label,
                active = entry == tab,
                accent = FdAccent.GREEN,
                modifier = Modifier.weight(1f),
            ) { onTab(entry) }
        }
        FdKey("+ ADD", active = true, accent = FdAccent.AMBER, modifier = Modifier.weight(1.1f), onClick = onAdd)
    }
}

@Composable
private fun EmptyState(tab: FeedTab, searching: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(FdDim.gutter),
        contentAlignment = Alignment.Center,
    ) {
        PanelText(
            when {
                searching -> "NO FEEDS MATCH THAT SEARCH"
                tab == FeedTab.FAV -> "NO FAVORITES YET — TAP ☆ ON ANY FEED"
                tab == FeedTab.SCAN -> "NO SCANNER FEEDS YET — ADD ONE WITH + ADD"
                else -> "NOTHING ON THIS BAND"
            },
            color = FlightDeck.textFaint,
            size = FdType.control,
        )
    }
}

/** "Boston Logan Tower" → "TWR", so the ident line reads like a radio panel. */
private fun facilityWord(name: String): String {
    val upper = name.uppercase()
    return when {
        "GROUND" in upper -> "GND"
        "TOWER" in upper || upper.endsWith(" TWR") -> "TWR"
        "APPROACH" in upper -> "APP"
        "DEPARTURE" in upper -> "DEP"
        "CLEARANCE" in upper || "DELIVERY" in upper -> "DEL"
        else -> "ATC"
    }
}

private fun formatNm(nm: Double): String = "${nm.roundToInt()} NM"

/**
 * Signal bars stand in for reception quality, which no feed source reports. Distance is the one
 * honest proxy available: a tower 5 NM away is genuinely more relevant than one 500 NM away.
 */
private fun signalFor(distanceNm: Double?): Int = when {
    distanceNm == null -> 2
    distanceNm < 50 -> 3
    distanceNm < 200 -> 2
    else -> 1
}

private fun tabSubtitle(tab: FeedTab): String = when (tab) {
    FeedTab.NRST -> "SORTED BY DIST"
    FeedTab.ATC -> "AIR TRAFFIC"
    FeedTab.SCAN -> "SCANNER"
    FeedTab.FAV -> "FAVORITES"
}

private fun feedsFor(
    repo: FeedRepository,
    tab: FeedTab,
    query: String,
    location: LatLng?,
): List<Feed> {
    if (query.isNotBlank()) return repo.search(query)
    return when (tab) {
        FeedTab.NRST -> repo.nearbyFeeds(location)
        FeedTab.ATC -> repo.feedsByType(FeedType.ATC)
        FeedTab.SCAN -> repo.feedsByType(FeedType.SCANNER)
        FeedTab.FAV -> repo.favoriteFeeds()
    }
}
