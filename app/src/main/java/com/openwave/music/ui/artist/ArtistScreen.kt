package com.openwave.music.ui.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.openwave.music.core.domain.Album
import com.openwave.music.core.domain.ArtistPage
import com.openwave.music.core.domain.Track
import com.openwave.music.presentation.ArtistViewModel
import com.openwave.music.ui.continuousMarquee
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ArtistScreen(
    onBack: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onPlayQueue: (List<Track>, Int) -> Unit,
    vm: ArtistViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val page by vm.page.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val albumLoading by vm.albumLoading.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Quay lại",
                )
            }
            Text(
                text = "Nghệ sĩ",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurfaceVariant,
            )
        }

        when {
            loading && page == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            error != null && page == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(error.orEmpty(), color = scheme.error)
                    TextButton(onClick = { vm.refresh() }) { Text("Thử lại") }
                }
            }
            page != null -> {
                val p = page!!
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 100.dp),
                ) {
                    item {
                        if (loading || albumLoading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    item {
                        ArtistHeader(page = p)
                    }

                    item { SectionTitle("Nổi bật nhất") }
                    if (p.highlights.isNotEmpty()) {
                        itemsIndexed(p.highlights, key = { _, t -> t.id }) { index, track ->
                            HighlightRow(
                                rank = index + 1,
                                track = track,
                                onClick = { onPlayTrack(track) },
                            )
                        }
                    }

                    item {
                        Spacer(Modifier.height(12.dp))
                        SectionTitle("Album")
                    }
                    if (p.albums.isNotEmpty()) {
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(p.albums, key = { it.id }) { album ->
                                    AlbumCard(
                                        album = album,
                                        onClick = {
                                            scope.launch {
                                                val tracks = vm.loadAlbumTracks(album)
                                                if (tracks.isNotEmpty()) {
                                                    onPlayQueue(tracks, 0)
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistHeader(page: ArtistPage) {
    val scheme = MaterialTheme.colorScheme
    val artist = page.artist
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(CircleShape)
                .background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (artist.imageUrl != null) {
                AsyncImage(
                    model = artist.imageUrl,
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = artist.name.take(1).uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.displayMedium,
                    color = scheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.headlineMedium,
            color = scheme.onSurface,
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 12.dp, bottom = 8.dp),
    )
}

@Composable
private fun HighlightRow(
    rank: Int,
    track: Track,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = rank.toString().padStart(2, '0'),
            style = MaterialTheme.typography.titleMedium,
            color = scheme.primary,
            modifier = Modifier.width(28.dp),
        )
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.surfaceVariant),
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
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.primary,
                )
            }
        }
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .continuousMarquee(),
        )
    }
}

@Composable
private fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceVariant,
        modifier = Modifier.width(148.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(scheme.primaryContainer),
            ) {
                if (album.coverUrl != null) {
                    AsyncImage(
                        model = album.coverUrl,
                        contentDescription = album.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = album.title.take(1).uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.headlineMedium,
                        color = scheme.onPrimaryContainer,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Text(
                text = album.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}
