package com.openwave.music.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.openwave.music.core.domain.BrowseItem
import com.openwave.music.core.domain.Track
import com.openwave.music.presentation.HomeViewModel
import com.openwave.music.ui.continuousMarquee
import java.util.Calendar
import java.util.Locale

/** Soft meadow palette inspired by the cat-in-headphones hero. */
private object HomeMeadow {
    val GrassDeep = Color(0xFF3D6B4F)
    val GrassMid = Color(0xFF6FAF7A)
    val GrassSoft = Color(0xFFC8E6C9)
    val GrassPale = Color(0xFFE8F5E9)
    val Blush = Color(0xFFFF8FAB)
    val BlushSoft = Color(0xFFFFC1CC)
    val BlushPale = Color(0xFFFFE4EC)
    val Cream = Color(0xFFFFFBF5)
    val CreamCard = Color(0xFFFFFFF8)
    val Ink = Color(0xFF2C3E2D)
    val InkMuted = Color(0xFF5C6B5E)
    val RankPink = Color(0xFFE85A7A)
}

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
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val loadMoreGeneration by vm.loadMoreGeneration.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, feed?.recommendations?.items?.size, loadMoreGeneration) {
        if (nearEnd) vm.loadMore()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        HomeMeadow.GrassPale,
                        HomeMeadow.Cream,
                        HomeMeadow.BlushPale.copy(alpha = 0.45f),
                        HomeMeadow.Cream,
                    ),
                ),
            ),
    ) {
        // Soft decorative flower dots (top corners)
        FlowerDots(modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 108.dp),
        ) {
            item {
                HomeGreetingHeader(
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
                        CircularProgressIndicator(color = HomeMeadow.Blush)
                    }
                }
            }

            if (error != null && feed == null) {
                item {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                        Text(
                            text = error.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = HomeMeadow.RankPink,
                        )
                        TextButton(onClick = { vm.refresh() }) {
                            Text("Thử lại", color = HomeMeadow.GrassDeep)
                        }
                    }
                }
            }

            feed?.let { home ->
                val listenItems = home.listenAgain.items.filterIsInstance<BrowseItem.TrackItem>()
                if (listenItems.isNotEmpty()) {
                    item {
                        MeadowSectionTitle(
                            title = home.listenAgain.title.ifBlank { "Nghe lại" },
                            emoji = "⏪",
                        )
                    }
                    item {
                        RecommendationRow(
                            items = listenItems,
                            onPlay = onPlayTrack,
                        )
                    }
                }

                item {
                    MeadowSectionTitle(
                        title = home.recommendations.title.ifBlank { "Dành cho bạn" },
                        emoji = "🎧",
                    )
                }
                val recommendationBatches = home.recommendations.items
                    .filterIsInstance<BrowseItem.TrackItem>()
                    .chunked(10)
                items(
                    recommendationBatches,
                    key = { batch -> "personalized-${batch.first().id}" },
                ) { batch ->
                    RecommendationRow(items = batch, onPlay = onPlayTrack)
                    Spacer(Modifier.height(18.dp))
                }
                if (loadingMore) item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = HomeMeadow.Blush)
                    }
                }
            }
        }
    }
}

@Composable
fun TrendingScreen(
    onPlayTrack: (Track) -> Unit,
    onArtistClick: (BrowseItem.ArtistItem) -> Unit,
    vm: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier.fillMaxSize().background(HomeMeadow.Cream).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 108.dp),
    ) {
        item {
            MeadowSectionTitle(
                title = "Xu hướng",
                emoji = "🔥",
                subtitle = feed?.topSongs?.subtitle ?: feed?.chartRegionLabel ?: feed?.chartDateLabel,
            )
        }
        if (loading && feed == null) item {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = HomeMeadow.Blush)
            }
        }
        error?.takeIf { feed == null }?.let { message -> item {
            Column(Modifier.padding(20.dp)) {
                Text(message, color = HomeMeadow.RankPink)
                TextButton(onClick = vm::refresh) { Text("Thử lại") }
            }
        } }
        feed?.let { home ->
            item { MeadowSectionTitle(home.topSongs.title.ifBlank { "Bài hát hàng đầu" }, compact = true) }
            items(home.topSongs.items.filterIsInstance<BrowseItem.TrackItem>(), key = { "song-${it.id}" }) { item ->
                RankedTrackRow(item, onClick = { onPlayTrack(item.track) })
            }
            item {
                Spacer(Modifier.height(12.dp))
                MeadowSectionTitle(home.topArtists.title.ifBlank { "Nghệ sĩ hàng đầu" }, emoji = "🌿")
            }
            item {
                ArtistRow(home.topArtists.items.filterIsInstance<BrowseItem.ArtistItem>(), onArtistClick)
            }
        }
    }
}

@Composable
private fun FlowerDots(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            HomeMeadow.Blush,
            HomeMeadow.BlushSoft,
            HomeMeadow.GrassMid.copy(alpha = 0.55f),
            HomeMeadow.Blush.copy(alpha = 0.7f),
        ).forEachIndexed { i, c ->
            Box(
                modifier = Modifier
                    .offset(y = (i % 2 * 4).dp)
                    .size((8 + i % 3 * 3).dp)
                    .clip(CircleShape)
                    .background(c),
            )
        }
    }
}

@Composable
private fun HomeGreetingHeader(
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greet = when (hour) {
        in 5..10 -> "Chào buổi sáng"
        in 11..13 -> "Chào buổi trưa"
        in 14..17 -> "Chào buổi chiều"
        else -> "Chào buổi tối"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = greet,
                    style = MaterialTheme.typography.titleMedium,
                    color = HomeMeadow.InkMuted,
                )
                Text(
                    text = "OpenWave",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                    ),
                    color = HomeMeadow.Ink,
                )
            }
            Surface(
                onClick = onRefresh,
                enabled = !loading,
                shape = CircleShape,
                color = HomeMeadow.CreamCard,
                shadowElevation = 2.dp,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = HomeMeadow.Blush,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "Làm mới",
                            tint = HomeMeadow.GrassDeep,
                        )
                    }
                }
            }
        }
        if (loading) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp)),
                color = HomeMeadow.Blush,
                trackColor = HomeMeadow.BlushPale,
            )
        }
    }
}

@Composable
private fun MeadowSectionTitle(
    title: String,
    emoji: String? = null,
    subtitle: String? = null,
    compact: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(
                top = if (compact) 6.dp else 18.dp,
                bottom = if (compact) 6.dp else 10.dp,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (emoji != null) {
                Text(text = emoji, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = title,
                style = if (compact) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                },
                color = HomeMeadow.Ink,
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = HomeMeadow.InkMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun RecommendationRow(
    items: List<BrowseItem.TrackItem>,
    onPlay: (Track) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
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
    Column(
        modifier = Modifier
            .width(152.dp)
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(22.dp),
                ambientColor = HomeMeadow.GrassDeep.copy(alpha = 0.12f),
                spotColor = HomeMeadow.Blush.copy(alpha = 0.2f),
            )
            .clip(RoundedCornerShape(22.dp))
            .background(HomeMeadow.CreamCard)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(HomeMeadow.GrassSoft),
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
                    color = HomeMeadow.GrassDeep,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            // Soft play pill
            Surface(
                shape = CircleShape,
                color = HomeMeadow.CreamCard.copy(alpha = 0.92f),
                shadowElevation = 2.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Phát",
                        tint = HomeMeadow.RankPink,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            color = HomeMeadow.Ink,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .continuousMarquee(),
        )
    }
}

@Composable
private fun RankedTrackRow(
    item: BrowseItem.TrackItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(18.dp),
                ambientColor = HomeMeadow.GrassDeep.copy(alpha = 0.08f),
            )
            .clip(RoundedCornerShape(18.dp))
            .background(HomeMeadow.CreamCard.copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = (item.rank ?: 0).toString().padStart(2, '0'),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = HomeMeadow.RankPink,
            modifier = Modifier.width(30.dp),
        )
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(HomeMeadow.GrassSoft)
                .border(1.5.dp, HomeMeadow.BlushSoft.copy(alpha = 0.55f), RoundedCornerShape(14.dp)),
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
                    color = HomeMeadow.GrassDeep,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                color = HomeMeadow.Ink,
                modifier = Modifier.continuousMarquee(),
            )
            item.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    color = HomeMeadow.InkMuted,
                    modifier = Modifier.continuousMarquee(),
                )
            }
        }
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(100.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .shadow(
                    elevation = 6.dp,
                    shape = CircleShape,
                    ambientColor = HomeMeadow.Blush.copy(alpha = 0.25f),
                )
                .clip(CircleShape)
                .border(3.dp, HomeMeadow.BlushSoft, CircleShape)
                .background(HomeMeadow.GrassSoft),
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
                    color = HomeMeadow.GrassDeep,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = HomeMeadow.Ink,
        )
        item.subtitle?.takeIf { it.isNotBlank() }?.let { views ->
            Text(
                text = views,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                color = HomeMeadow.InkMuted,
                modifier = Modifier.continuousMarquee(),
            )
        }
    }
}
