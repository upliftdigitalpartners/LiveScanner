package dev.fahim.livescanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fahim.livescanner.data.Feed
import dev.fahim.livescanner.data.FeedRepository
import dev.fahim.livescanner.data.FeedType
import dev.fahim.livescanner.data.LatLng
import dev.fahim.livescanner.data.distanceKmFrom
import kotlin.math.roundToInt

enum class FeedTab(val label: String) {
    NEARBY("Nearby"),
    ATC("Air Traffic"),
    SCANNER("Scanner"),
    FAVORITES("Favorites"),
    ALL("All"),
}

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onAddFeed: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRadar: (Feed) -> Unit,
) {
    val allFeeds by vm.allFeeds.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val customFeeds by vm.customFeeds.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(FeedTab.NEARBY) }

    val locationLauncher = rememberLocationPermissionLauncher { granted ->
        if (granted) vm.refreshLocation()
    }

    val feeds = remember(tab, query, location, allFeeds, favorites, customFeeds) {
        feedsFor(vm.repository, tab, query, location)
    }
    val customIds = remember(customFeeds) { customFeeds.map { it.id }.toSet() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Scanner", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            val current = playback.currentMediaId?.let { id -> allFeeds.firstOrNull { it.id == id } }
            NowPlayingBar(
                state = playback,
                onRadar = current?.takeIf { it.hasCoordinates }?.let { feed -> { onOpenRadar(feed) } },
                onToggle = vm::togglePlayPause,
                onStop = vm::stop,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddFeed,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add feed") },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Search feeds, cities, airports…") },
            )

            if (query.isBlank()) {
                TabRow(selected = tab, onSelect = { tab = it })
                if (tab == FeedTab.NEARBY && !vm.hasLocationPermission()) {
                    LocationPrompt(onEnable = { locationLauncher() })
                }
            }

            if (feeds.isEmpty()) {
                EmptyState(tab = tab, searching = query.isNotBlank(), onAddFeed = onAddFeed)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items = feeds, key = { it.id }) { feed ->
                        FeedRow(
                            feed = feed,
                            isCurrent = playback.currentMediaId == feed.id,
                            isPlaying = playback.isPlaying && playback.currentMediaId == feed.id,
                            isFavorite = feed.id in favorites,
                            removable = feed.id in customIds,
                            distanceKm = feed.distanceKmFrom(location),
                            onPlay = { vm.play(feed) },
                            onToggleFavorite = { vm.toggleFavorite(feed.id) },
                            onRemove = { vm.removeFeed(feed.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabRow(selected: FeedTab, onSelect: (FeedTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FeedTab.entries.forEach { t ->
            FilterChip(
                selected = t == selected,
                onClick = { onSelect(t) },
                label = { Text(t.label) },
            )
        }
    }
}

@Composable
private fun LocationPrompt(onEnable: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            "Allow location to sort feeds by distance.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onEnable) { Text("Enable") }
    }
}

@Composable
private fun FeedRow(
    feed: Feed,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    removable: Boolean,
    distanceKm: Double?,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRemove: () -> Unit,
) {
    val container =
        if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    Surface(color = container, onClick = onPlay) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = typeIcon(feed.type),
                contentDescription = null,
                tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    feed.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    buildString {
                        append(feed.subtitle)
                        if (distanceKm != null) append("  ·  ${formatDistance(distanceKm)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isPlaying) {
                LiveDot()
                Spacer(Modifier.width(4.dp))
            }
            if (removable) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove feed", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (isFavorite) "Unfavorite" else "Favorite",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingBar(
    state: PlaybackUiState,
    onRadar: (() -> Unit)?,
    onToggle: () -> Unit,
    onStop: () -> Unit,
) {
    if (state.currentMediaId == null) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.title ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusLine(state)
            }
            if (state.isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            onRadar?.let { open ->
                IconButton(onClick = open) {
                    Icon(Icons.Default.Radar, contentDescription = "Radar")
                }
            }
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = "Stop")
            }
        }
    }
}

@Composable
private fun StatusLine(state: PlaybackUiState) {
    when {
        state.errorMessage != null -> Text(
            state.errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        state.isBuffering -> Text(
            "Buffering…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.isPlaying -> Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot()
            Spacer(Modifier.width(6.dp))
            Text("LIVE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.subtitle != null) {
                Text("  ·  ${state.subtitle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        else -> Text(
            "Paused",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveDot() {
    Box(
        Modifier
            .size(8.dp)
            .background(Color(0xFFE5484D), CircleShape),
    )
}

@Composable
private fun EmptyState(tab: FeedTab, searching: Boolean, onAddFeed: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val message = when {
            searching -> "No feeds match your search."
            tab == FeedTab.FAVORITES -> "No favorites yet. Tap the ☆ on any feed."
            tab == FeedTab.SCANNER -> "No scanner feeds yet.\nAdd a Broadcastify feed or a direct stream URL."
            else -> "Nothing here yet."
        }
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tab == FeedTab.SCANNER || tab == FeedTab.ALL) {
            Spacer(Modifier.size(16.dp))
            TextButton(onClick = onAddFeed) { Text("Add a feed") }
        }
    }
}

private fun typeIcon(type: FeedType): ImageVector = when (type) {
    FeedType.ATC -> Icons.Default.Flight
    FeedType.SCANNER -> Icons.Default.Radio
    FeedType.OTHER -> Icons.Default.Radio
}

private fun formatDistance(km: Double): String =
    if (km < 1.0) "${(km * 1000).roundToInt()} m" else "${km.roundToInt()} km"

private fun feedsFor(
    repo: FeedRepository,
    tab: FeedTab,
    query: String,
    location: LatLng?,
): List<Feed> {
    if (query.isNotBlank()) return repo.search(query)
    return when (tab) {
        FeedTab.NEARBY -> repo.nearbyFeeds(location)
        FeedTab.ATC -> repo.feedsByType(FeedType.ATC)
        FeedTab.SCANNER -> repo.feedsByType(FeedType.SCANNER)
        FeedTab.FAVORITES -> repo.favoriteFeeds()
        FeedTab.ALL -> repo.allFeeds.value.sortedBy { it.name }
    }
}
