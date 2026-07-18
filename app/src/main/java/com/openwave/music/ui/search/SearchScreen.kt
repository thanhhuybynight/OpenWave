package com.openwave.music.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openwave.music.core.domain.SourcePolicy
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.UnifiedTrack
import com.openwave.music.presentation.SearchViewModel

@Composable
fun SearchScreen(
    onPlayTrack: (Track) -> Unit,
    onPlayUnified: (UnifiedTrack) -> Unit = { onPlayTrack(it.track) },
    onPrefetch: (Track) -> Unit = {},
    vm: SearchViewModel = hiltViewModel(),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val batch by vm.batch.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            placeholder = { Text("Search all free sources") },
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

        // Source status strip — which apps already answered
        if (query.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                batch.completedSources.forEach { src ->
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = {
                            Text(
                                SourcePolicy.displayName(src).substringBefore(" "),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
                if (batch.pendingSources.isNotEmpty()) {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(batch.tracks, key = { "${it.track.source}:${it.track.id}" }) { hit ->
                LaunchedEffect(hit.track.id) {
                    // Prefetch stream while row is composed (visible-ish)
                    if (SourcePolicy.canStreamAnonymously(hit.track.source)) {
                        onPrefetch(hit.track)
                    }
                }
                UnifiedTrackRow(
                    hit = hit,
                    onClick = { onPlayUnified(hit) },
                )
            }

            if (query.isNotBlank() && batch.isComplete && batch.tracks.isEmpty()) {
                item {
                    Text(
                        text = "No results yet. Free extractors land in Phase 2; demo search works from Home.",
                        style = MaterialTheme.typography.bodyMedium,
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
) {
    val track = hit.track
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                    append(SourcePolicy.displayName(track.source))
                    if (hit.alternates.isNotEmpty()) {
                        append(" +")
                        append(hit.alternates.size)
                        append(" more")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
    }
}
