package com.openwave.music.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openwave.music.core.domain.BrowseItem
import com.openwave.music.core.domain.BrowseShelf
import com.openwave.music.core.domain.StreamQuality
import com.openwave.music.core.domain.Track
import com.openwave.music.presentation.HomeViewModel
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun HomeScreen(
    onPlayDemo: () -> Unit,
    onPlayTrack: (Track) -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val shelves by vm.shelves.collectAsStateWithLifecycle()
    val sleep by vm.sleepState.collectAsStateWithLifecycle()
    val crossfade by vm.crossfadeSettings.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(
                    text = "OpenWave",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "One hub for free music sources. Guest by default; YTM Premium optional.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPlayDemo) { Text("Play demo") }
                    OutlinedButton(onClick = { vm.refresh() }) { Text("Refresh") }
                }
            }
        }

        // Quick tools: sleep / quality / crossfade
        item {
            Text(
                text = "Player tools",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(5, 15, 30, 45, 60).forEach { min ->
                    AssistChip(
                        onClick = { vm.startSleepTimer(min) },
                        label = { Text("${min}m sleep") },
                    )
                }
                if (sleep.active) {
                    AssistChip(
                        onClick = { vm.cancelSleepTimer() },
                        label = {
                            Text("Cancel ${formatRemain(sleep.remainingMs)}")
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StreamQuality.entries.forEach { q ->
                    FilterChip(
                        selected = false,
                        onClick = { vm.setQuality(q) },
                        label = {
                            Text(
                                when (q) {
                                    StreamQuality.AUTO -> "Quality Auto"
                                    StreamQuality.HIGH -> "High"
                                    StreamQuality.MAX -> "Max 256k*"
                                },
                            )
                        },
                    )
                }
                FilterChip(
                    selected = crossfade.enabled,
                    onClick = { vm.setCrossfade(!crossfade.enabled) },
                    label = { Text(if (crossfade.enabled) "Crossfade on" else "Crossfade") },
                )
            }
            Text(
                text = "* Max 256 kbps needs optional YTM Premium session. Guest uses best free format.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
        }

        // Browse shelves (Home / Charts / Podcasts / Moods)
        items(shelves, key = { it.id }) { shelf ->
            ShelfSection(
                shelf = shelf,
                onTrack = onPlayTrack,
                onDownload = { vm.download(it) },
            )
        }

        item {
            FeatureRoadmapCard(Modifier.padding(24.dp))
        }
    }
}

@Composable
private fun ShelfSection(
    shelf: BrowseShelf,
    onTrack: (Track) -> Unit,
    onDownload: (Track) -> Unit,
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            text = shelf.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (shelf.items.isEmpty()) {
            Text(
                text = "Connect YTM browse (Phase 2A) to fill this shelf.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shelf.items, key = { it.id }) { item ->
                    when (item) {
                        is BrowseItem.TrackItem -> TrackChip(
                            item = item,
                            onClick = { onTrack(item.track) },
                            onLongClickDownload = { onDownload(item.track) },
                        )
                        else -> CategoryChip(title = item.title, subtitle = item.subtitle)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackChip(
    item: BrowseItem.TrackItem,
    onClick: () -> Unit,
    onLongClickDownload: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(148.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.subtitle.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onLongClickDownload, modifier = Modifier.padding(top = 4.dp)) {
                Text("Save offline")
            }
        }
    }
}

@Composable
private fun CategoryChip(title: String, subtitle: String?) {
    Card(
        modifier = Modifier
            .width(148.dp)
            .height(100.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun FeatureRoadmapCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("SimpMusic-parity roadmap", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val lines = listOf(
                "Ready: Sleep timer, quality prefs, crossfade flag, offline queue, scrobble, SB/RYD clients, Auto shell",
                "Next: YTM browse + stream, real downloads, playlist UI, video/Canvas/AI",
                "Optional login: Premium 256kbps, library sync, artist notifications",
            )
            lines.forEach {
                Text(
                    "· $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

private fun formatRemain(ms: Long): String {
    val m = TimeUnit.MILLISECONDS.toMinutes(ms)
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}
