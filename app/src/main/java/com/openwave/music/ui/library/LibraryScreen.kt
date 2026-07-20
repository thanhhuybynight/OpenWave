package com.openwave.music.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
import com.openwave.music.core.domain.LocalPlaylist
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.TrackDisplay
import com.openwave.music.presentation.FavoritesPlaylistId
import com.openwave.music.presentation.LibraryViewModel
import com.openwave.music.ui.continuousMarquee
import java.util.Locale

@Composable
fun LibraryScreen(
    onPlayTrack: (Track) -> Unit,
    onPlayQueue: (List<Track>, Int) -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
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

    BackHandler(enabled = selectedId != null) {
        vm.closePlaylist()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        // Parent NavHost already applies bottom-bar insets via Scaffold padding;
        // only use top status-bar inset here to avoid double bottom padding / overlap.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .statusBarsPadding(),
        ) {
            AnimatedContent(
                targetState = selectedId,
                transitionSpec = {
                    if (targetState != null) {
                        (slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(280)))
                            .togetherWith(
                                slideOutHorizontally(tween(280)) { -it / 5 } + fadeOut(tween(200)),
                            )
                    } else {
                        (slideInHorizontally(tween(280)) { -it / 5 } + fadeIn(tween(280)))
                            .togetherWith(
                                slideOutHorizontally(tween(280)) { it / 3 } + fadeOut(tween(200)),
                            )
                    }
                },
                label = "library_playlist",
                modifier = Modifier.fillMaxSize(),
            ) { openId ->
                if (openId != null) {
                    PlaylistDetail(
                        title = vm.playlistTitle(openId),
                        tracks = playlistTracks,
                        isFavorites = openId == FavoritesPlaylistId,
                        onBack = vm::closePlaylist,
                        onPlayAll = {
                            if (playlistTracks.isNotEmpty()) onPlayQueue(playlistTracks, 0)
                        },
                        onPlayTrack = { index, _ ->
                            onPlayQueue(playlistTracks, index)
                        },
                        onRemove = { trackId ->
                            vm.removeFromPlaylist(openId, trackId)
                        },
                        onDeletePlaylist = {
                            if (openId != FavoritesPlaylistId) vm.deletePlaylist(openId)
                        },
                        onRename = {
                            if (openId == FavoritesPlaylistId) return@PlaylistDetail
                            val pl = playlists.firstOrNull { it.id == openId }
                            if (pl != null) renameTarget = pl
                        },
                    )
                } else {
                    LibraryHome(
                        playlists = playlists,
                        favoriteCount = favorites.size,
                        onOpenFavorites = vm::openFavorites,
                        onOpen = vm::openPlaylist,
                        onCreate = { showCreate = true },
                        onDelete = vm::deletePlaylist,
                    )
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
private fun LibraryHome(
    playlists: List<LocalPlaylist>,
    favoriteCount: Int,
    onOpenFavorites: () -> Unit,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 120.dp, // room for mini-player + nav bar
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = "Library",
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
        item {
            FavoritesRow(
                count = favoriteCount,
                onClick = onOpenFavorites,
            )
        }
        item {
            FilledTonalButton(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Tạo playlist")
            }
        }
        if (playlists.isEmpty()) {
            item {
                EmptyHint("Chưa có playlist. Tạo mới hoặc thêm bài từ Search.")
            }
        }
        items(playlists, key = { it.id }) { pl ->
            PlaylistRow(
                playlist = pl,
                onClick = { onOpen(pl.id) },
                onDelete = { onDelete(pl.id) },
            )
        }
    }
}

@Composable
private fun FavoritesRow(
    count: Int,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
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
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Yêu thích",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (count == 0) "Chưa có bài hát" else "$count bài hát",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
            Text(
                playlist.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Xóa") },
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
    isFavorites: Boolean = false,
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
                .padding(horizontal = 4.dp, vertical = 4.dp),
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
            if (!isFavorites) {
                TextButton(onClick = onRename) { Text("Đổi tên") }
                IconButton(onClick = onDeletePlaylist) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Xóa playlist")
                }
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
                Text("Phát tất cả (${tracks.size})")
            }
        } else {
            EmptyHint(
                if (isFavorites) {
                    "Chưa có bài yêu thích. Nhấn trái tim trên kết quả tìm kiếm."
                } else {
                    "Playlist trống. Thêm bài từ Search."
                },
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            items(tracks.size, key = { tracks[it].id }) { index ->
                val track = tracks[index]
                TrackLine(
                    title = track.title,
                    subtitle = TrackDisplay.subtitle(track),
                    coverUrl = track.coverUrl,
                    trailing = {
                        IconButton(onClick = { onRemove(track.id) }) {
                            Icon(
                                if (isFavorites) Icons.Filled.Favorite else Icons.Outlined.Delete,
                                contentDescription = if (isFavorites) "Bỏ yêu thích" else "Xóa",
                                tint = if (isFavorites) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
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
private fun TrackLine(
    title: String,
    subtitle: String = "",
    coverUrl: String?,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title.take(1).uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.continuousMarquee(),
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.continuousMarquee(),
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
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
        title = { Text("Playlist mới") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("Tên") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title.ifBlank { "My playlist" }) },
            ) { Text("Tạo") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
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
        title = { Text("Đổi tên playlist") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("Tên") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title) }) { Text("Lưu") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
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
        title = { Text("Thêm vào playlist") },
        text = {
            Column {
                if (creating) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Tên playlist mới") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    if (playlists.isEmpty()) {
                        Text(
                            "Chưa có playlist.",
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
                        Text("Tạo mới…")
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(
                    onClick = { onCreateAndAdd(newTitle.ifBlank { "My playlist" }) },
                ) { Text("Tạo & thêm") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
        },
    )
}
