package com.openwave.music.ui.library

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.openwave.music.core.domain.DownloadState
import com.openwave.music.core.domain.LocalPlaylist
import com.openwave.music.core.domain.OfflineTrack
import com.openwave.music.core.domain.ScrobbleEntry
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.TrackStats
import com.openwave.music.presentation.LibraryTab
import com.openwave.music.presentation.LibraryViewModel
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onPlayTrack: (Track) -> Unit,
    onPlayQueue: (List<Track>, Int) -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val scrobbles by vm.scrobbles.collectAsStateWithLifecycle()
    val selectedId by vm.selectedPlaylistId.collectAsStateWithLifecycle()
    val playlistTracks by vm.playlistTracks.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<LocalPlaylist?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding(),
        ) {
            if (selectedId != null) {
                PlaylistDetail(
                    title = vm.playlistTitle(selectedId),
                    tracks = playlistTracks,
                    onBack = vm::closePlaylist,
                    onPlayAll = {
                        if (playlistTracks.isNotEmpty()) onPlayQueue(playlistTracks, 0)
                    },
                    onPlayTrack = { index, track ->
                        onPlayQueue(playlistTracks, index)
                    },
                    onRemove = { trackId ->
                        selectedId?.let { vm.removeFromPlaylist(it, trackId) }
                    },
                    onDeletePlaylist = {
                        selectedId?.let { vm.deletePlaylist(it) }
                    },
                    onRename = {
                        val pl = playlists.firstOrNull { it.id == selectedId }
                        if (pl != null) renameTarget = pl
                    },
                )
            } else {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LibraryTab.entries.forEach { t ->
                        FilterChip(
                            selected = tab == t,
                            onClick = { vm.selectTab(t) },
                            label = { Text(t.name) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                when (tab) {
                    LibraryTab.Playlists -> PlaylistsTab(
                        playlists = playlists,
                        onOpen = vm::openPlaylist,
                        onCreate = { showCreate = true },
                        onDelete = vm::deletePlaylist,
                    )
                    LibraryTab.Downloads -> DownloadsTab(
                        downloads = downloads,
                        onPlay = { onPlayTrack(it.track) },
                        onRemove = vm::removeDownload,
                    )
                    LibraryTab.Stats -> StatsTab(
                        stats = stats,
                        onPlay = { s ->
                            onPlayTrack(
                                Track(
                                    id = s.trackId,
                                    title = s.title,
                                    artists = listOf(
                                        com.openwave.music.core.domain.Artist(
                                            id = s.artist,
                                            name = s.artist,
                                            source = com.openwave.music.core.domain.MusicSource.UNKNOWN,
                                        ),
                                    ),
                                    source = com.openwave.music.core.domain.MusicSource.UNKNOWN,
                                ),
                            )
                        },
                    )
                    LibraryTab.History -> HistoryTab(scrobbles = scrobbles)
                }
            }
        }
    }

    if (showCreate) {
        CreatePlaylistDialog(
            onDismiss = { showCreate = false },
            onConfirm = { title ->
                showCreate = false
                vm.createPlaylist(title)
            },
        )
    }

    renameTarget?.let { pl ->
        RenamePlaylistDialog(
            initial = pl.title,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                vm.renamePlaylist(pl.id, title)
                renameTarget = null
            },
        )
    }
}

@Composable
private fun PlaylistsTab(
    playlists: List<LocalPlaylist>,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilledTonalButton(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New playlist")
            }
        }
        if (playlists.isEmpty()) {
            item {
                EmptyHint("No playlists yet. Create one, then add tracks from Search or Home.")
            }
        }
        items(playlists, key = { it.id }) { pl ->
            PlaylistRow(
                playlist = pl,
                onClick = { onOpen(pl.id) },
                onDelete = { onDelete(pl.id) },
            )
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun PlaylistRow(
    playlist: LocalPlaylist,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (playlist.coverUrl != null) {
                    AsyncImage(
                        model = playlist.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Outlined.PlaylistPlay,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    playlist.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Local playlist",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Delete, contentDescription = null)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetail(
    title: String,
    tracks: List<Track>,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onPlayTrack: (Int, Track) -> Unit,
    onRemove: (String) -> Unit,
    onDeletePlaylist: () -> Unit,
    onRename: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRename) { Text("Rename") }
            IconButton(onClick = onDeletePlaylist) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete playlist")
            }
        }

        if (tracks.isNotEmpty()) {
            FilledTonalButton(
                onClick = onPlayAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Play all (${tracks.size})")
            }
        } else {
            EmptyHint("This playlist is empty. Open Search, play a track, or use Add to playlist.")
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            items(tracks.size, key = { tracks[it].id }) { index ->
                val track = tracks[index]
                TrackLine(
                    title = track.title,
                    subtitle = track.artists.joinToString { it.name },
                    coverUrl = track.coverUrl,
                    trailing = {
                        IconButton(onClick = { onRemove(track.id) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Remove")
                        }
                    },
                    onClick = { onPlayTrack(index, track) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun DownloadsTab(
    downloads: List<OfflineTrack>,
    onPlay: (OfflineTrack) -> Unit,
    onRemove: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        if (downloads.isEmpty()) {
            item {
                EmptyHint("No downloads. On Home or Search, tap Save offline on a track.")
            }
        }
        items(downloads, key = { it.trackId }) { d ->
            val stateLabel = when (d.state) {
                DownloadState.COMPLETED -> formatBytes(d.bytes)
                DownloadState.DOWNLOADING -> "Downloading…"
                DownloadState.QUEUED -> "Queued"
                DownloadState.FAILED -> "Failed"
                DownloadState.PAUSED -> "Paused"
            }
            TrackLine(
                title = d.track.title,
                subtitle = "${d.track.artists.joinToString { it.name }} · $stateLabel",
                coverUrl = d.track.coverUrl,
                leadingIcon = {
                    Icon(
                        Icons.Outlined.DownloadDone,
                        contentDescription = null,
                        tint = when (d.state) {
                            DownloadState.COMPLETED -> MaterialTheme.colorScheme.primary
                            DownloadState.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                trailing = {
                    IconButton(onClick = { onRemove(d.trackId) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove download")
                    }
                },
                onClick = {
                    if (d.state == DownloadState.COMPLETED) onPlay(d)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun StatsTab(
    stats: List<TrackStats>,
    onPlay: (TrackStats) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        if (stats.isEmpty()) {
            item { EmptyHint("Play some music to build your listening stats.") }
        }
        items(stats, key = { it.trackId }) { s ->
            TrackLine(
                title = s.title,
                subtitle = "${s.artist} · ${s.playCount} plays · ${formatDuration(s.totalListenedMs)}",
                coverUrl = null,
                onClick = { onPlay(s) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun HistoryTab(scrobbles: List<ScrobbleEntry>) {
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        if (scrobbles.isEmpty()) {
            item {
                EmptyHint("Local scrobbles appear after you listen past ~half a track.")
            }
        }
        items(scrobbles, key = { it.id }) { s ->
            TrackLine(
                title = s.title,
                subtitle = "${s.artist} · ${formatRelative(s.startedAtMs)}",
                coverUrl = null,
                leadingIcon = {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {},
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun TrackLine(
    title: String,
    subtitle: String,
    coverUrl: String?,
    onClick: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            coverUrl != null -> {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
            leadingIcon != null -> {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    leadingIcon()
                }
            }
            else -> {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        title.take(1).uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}

@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title.ifBlank { "My playlist" }) },
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RenamePlaylistDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename playlist") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Dialog used from Search/Home to add current track to a playlist. */
@Composable
fun AddToPlaylistDialog(
    playlists: List<LocalPlaylist>,
    onDismiss: () -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onPick: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                if (creating) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("New playlist name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    if (playlists.isEmpty()) {
                        Text(
                            "No playlists yet.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    playlists.forEach { pl ->
                        Text(
                            text = pl.title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(pl.id) }
                                .padding(vertical = 12.dp),
                        )
                    }
                    TextButton(onClick = { creating = true }) {
                        Text("Create new…")
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(
                    onClick = { onCreateAndAdd(newTitle.ifBlank { "My playlist" }) },
                ) { Text("Create & add") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}

private fun formatDuration(ms: Long): String {
    val m = TimeUnit.MILLISECONDS.toMinutes(ms)
    if (m < 60) return "${m}m listened"
    val h = m / 60
    return "${h}h ${m % 60}m listened"
}

private fun formatRelative(epochMs: Long): String {
    val delta = System.currentTimeMillis() - epochMs
    val min = TimeUnit.MILLISECONDS.toMinutes(delta)
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 24 * 60 -> "${min / 60}h ago"
        else -> "${min / (24 * 60)}d ago"
    }
}
