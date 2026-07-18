package com.openwave.music.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.openwave.music.core.domain.Track
import com.openwave.music.presentation.HomeViewModel

@Composable
fun HomeScreen(
    onPlayDemo: () -> Unit,
    onPlayTrack: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val shelves by vm.shelves.collectAsStateWithLifecycle()

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

        items(shelves, key = { it.id }) { shelf ->
            ShelfSection(
                shelf = shelf,
                onTrack = onPlayTrack,
                onDownload = { vm.download(it) },
                onAddToPlaylist = onAddToPlaylist,
            )
        }
    }
}

@Composable
private fun ShelfSection(
    shelf: BrowseShelf,
    onTrack: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            text = shelf.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (shelf.items.isEmpty()) {
            Text(
                text = "Pull to refresh or wait for network browse.",
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
                            onDownload = { onDownload(item.track) },
                            onAdd = { onAddToPlaylist(item.track) },
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
    onDownload: () -> Unit,
    onAdd: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(156.dp),
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
            TextButton(onClick = onDownload, modifier = Modifier.padding(top = 2.dp)) {
                Text("Save offline")
            }
            TextButton(onClick = onAdd) {
                Text("Add to playlist")
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
