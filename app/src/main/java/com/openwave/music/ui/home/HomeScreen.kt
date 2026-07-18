package com.openwave.music.ui.home

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.openwave.music.core.domain.BrowseItem
import com.openwave.music.core.domain.Track
import com.openwave.music.presentation.HomeViewModel
import java.util.Locale

@Composable
fun HomeScreen(
    onPlayTrack: (Track) -> Unit = {},
    onArtistClick: (BrowseItem.ArtistItem) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        item {
            HomeHeader(
                loading = loading,
                onRefresh = { vm.refresh() },
            )
        }

        if (loading && feed == null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        if (error != null && feed == null) {
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Text(
                        text = error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.error,
                    )
                    TextButton(onClick = { vm.refresh() }) {
                        Text("Thử lại")
                    }
                }
            }
        }

        feed?.let { home ->
            item { SectionTitle(home.recommendations.title) }
            if (home.recommendations.items.isNotEmpty()) {
                item {
                    RecommendationRow(
                        items = home.recommendations.items.filterIsInstance<BrowseItem.TrackItem>(),
                        onPlay = onPlayTrack,
                    )
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                SectionTitle("Hàng đầu")
            }

            item { SectionTitle(home.topSongs.title) }
            if (home.topSongs.items.isNotEmpty()) {
                items(
                    home.topSongs.items.filterIsInstance<BrowseItem.TrackItem>(),
                    key = { "song-${it.id}" },
                ) { item ->
                    RankedTrackRow(
                        item = item,
                        onClick = { onPlayTrack(item.track) },
                    )
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                SectionTitle(home.topArtists.title)
            }
            if (home.topArtists.items.isNotEmpty()) {
                item {
                    ArtistRow(
                        items = home.topArtists.items.filterIsInstance<BrowseItem.ArtistItem>(),
                        onClick = onArtistClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Trang chủ",
                style = MaterialTheme.typography.displayLarge,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "Làm mới",
                        tint = scheme.onSurface,
                    )
                }
            }
        }
        if (loading) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 10.dp),
    )
}

@Composable
private fun RecommendationRow(
    items: List<BrowseItem.TrackItem>,
    onPlay: (Track) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.id }) { item ->
            RecommendCard(item = item, onClick = { onPlay(item.track) })
        }
    }
}

@Composable
private fun RecommendCard(
    item: BrowseItem.TrackItem,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
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
                if (item.coverUrl != null) {
                    AsyncImage(
                        model = item.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = item.title.take(1).uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.headlineMedium,
                        color = scheme.onPrimaryContainer,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = scheme.onSurface,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun RankedTrackRow(
    item: BrowseItem.TrackItem,
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
            text = (item.rank ?: 0).toString().padStart(2, '0'),
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
            if (item.coverUrl != null) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    item.title.take(1).uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.primary,
                )
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ArtistRow(
    items: List<BrowseItem.ArtistItem>,
    onClick: (BrowseItem.ArtistItem) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items, key = { it.id }) { item ->
            ArtistAvatar(
                item = item,
                onClick = { onClick(item) },
            )
        }
    }
}

@Composable
private fun ArtistAvatar(
    item: BrowseItem.ArtistItem,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(96.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (item.coverUrl != null) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = item.title.take(1).uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = scheme.onSurface,
        )
    }
}
