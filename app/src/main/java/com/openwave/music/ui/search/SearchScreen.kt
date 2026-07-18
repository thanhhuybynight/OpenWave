package com.openwave.music.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.SourcePolicy
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.UnifiedTrack
import com.openwave.music.presentation.SearchViewModel
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun SearchScreen(
    onPlayTrack: (Track) -> Unit,
    onPlayUnified: (UnifiedTrack) -> Unit = { onPlayTrack(it.track) },
    onPrefetch: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    onStartStation: (Track) -> Unit = {},
    isResolving: Boolean = false,
    playError: String? = null,
    onClearError: () -> Unit = {},
    vm: SearchViewModel = hiltViewModel(),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val batch by vm.batch.collectAsStateWithLifecycle()
    val filter by vm.sourceFilter.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Search",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        Text(
            text = "YouTube · SoundCloud · Local demo",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        OutlinedTextField(
            value = query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            placeholder = { Text("Song, artist, remix…") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotBlank() && !batch.isComplete) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
        )

        // Source filters
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filter == null,
                onClick = { vm.setSourceFilter(null) },
                label = { Text("All") },
            )
            FilterChip(
                selected = filter?.contains(MusicSource.YOUTUBE_MUSIC) == true,
                onClick = { vm.toggleSource(MusicSource.YOUTUBE_MUSIC) },
                label = { Text("YouTube") },
            )
            FilterChip(
                selected = filter?.contains(MusicSource.SOUNDCLOUD) == true,
                onClick = { vm.toggleSource(MusicSource.SOUNDCLOUD) },
                label = { Text("SoundCloud") },
            )
            FilterChip(
                selected = filter?.contains(MusicSource.LOCAL) == true,
                onClick = { vm.toggleSource(MusicSource.LOCAL) },
                label = { Text("Demo") },
            )
        }

        // Status strip
        if (query.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!batch.isComplete) {
                    LinearProgressIndicator(modifier = Modifier.weight(1f).height(2.dp))
                }
                batch.completedSources.forEach { src ->
                    val err = batch.errorBySource[src]
                    Text(
                        text = SourcePolicy.displayName(src).substringBefore(" ") +
                            if (err != null) "!" else " ✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (err != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        if (isResolving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Text(
                "Resolving stream…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (playError != null) {
            Text(
                text = playError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClearError)
                    .padding(vertical = 6.dp),
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(
                batch.tracks,
                key = { "${it.track.source}:${it.track.id}" },
            ) { hit ->
                LaunchedEffect(hit.track.id) {
                    if (SourcePolicy.canStreamAnonymously(hit.track.source)) {
                        onPrefetch(hit.track)
                    }
                }
                UnifiedTrackRow(
                    hit = hit,
                    onClick = { onPlayUnified(hit) },
                    onAdd = { onAddToPlaylist(hit.track) },
                    onStation = { onStartStation(hit.track) },
                )
            }

            if (query.isNotBlank() && batch.isComplete && batch.tracks.isEmpty()) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "No results for \"$query\".",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Tips: check network, try YouTube-only filter, or search \"demo\" / \"night\" for local samples.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        batch.errorBySource.forEach { (src, err) ->
                            Text(
                                "${SourcePolicy.displayName(src)}: $err",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            if (query.isBlank()) {
                item {
                    Text(
                        text = "Try: lofi, daft punk, ambient, or a track title. Tap a result to play.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UnifiedTrackRow(
    hit: UnifiedTrack,
    onClick: () -> Unit,
    onAdd: () -> Unit,
    onStation: () -> Unit,
) {
    val track = hit.track
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (track.coverUrl != null) {
                AsyncImage(
                    model = track.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    track.title.take(1).uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.primary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(track.artists.joinToString { it.name })
                    append(" · ")
                    append(SourcePolicy.displayName(track.source).substringBefore(" "))
                    if (track.durationMs > 0) {
                        append(" · ")
                        append(formatDur(track.durationMs))
                    }
                    if (hit.alternates.isNotEmpty()) {
                        append(" · +")
                        append(hit.alternates.size)
                        append(" src")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClick) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = scheme.primary,
            )
        }
        IconButton(onClick = onStation) {
            Icon(
                Icons.Outlined.HourglassEmpty,
                contentDescription = "Start station",
                tint = scheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onAdd) {
            Icon(
                Icons.AutoMirrored.Outlined.PlaylistAdd,
                contentDescription = "Add to playlist",
                tint = scheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDur(ms: Long): String {
    val m = TimeUnit.MILLISECONDS.toMinutes(ms)
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}
