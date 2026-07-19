package com.openwave.music.features.home

import android.util.Log
import com.openwave.music.core.domain.BrowseItem
import com.openwave.music.core.domain.BrowseShelf
import com.openwave.music.core.domain.BrowseShelfKind
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.MusicSourceClient
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.TrackStats
import com.openwave.music.data.source.newpipe.NewPipeBootstrap
import com.openwave.music.data.source.youtube.YouTubeMusicSourceClient
import com.openwave.music.features.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Đề xuất" — seeds from local play history, then expands with
 * related / search results the user has not played yet.
 */
@Singleton
class HistoryRecommendationEngine @Inject constructor(
    private val library: LibraryRepository,
    private val clients: Set<@JvmSuppressWildcards MusicSourceClient>,
    private val newPipe: NewPipeBootstrap,
) {
    suspend fun recommendations(limit: Int = 24): BrowseShelf = withContext(Dispatchers.IO) {
        val stats = runCatching { library.stats().first() }.getOrDefault(emptyList())
        val playedIds = stats.map { it.trackId }.toHashSet()

        if (stats.isEmpty()) {
            return@withContext BrowseShelf(
                id = "de_xuat",
                title = "Đề xuất",
                kind = BrowseShelfKind.RECOMMENDATIONS,
                subtitle = "Nghe vài bài để OpenWave học gu nhạc của bạn",
                items = emptyList(),
            )
        }

        val topArtists = stats
            .groupBy { it.artist.ifBlank { "?" } }
            .mapValues { (_, rows) -> rows.sumOf { it.playCount } }
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .filter { it != "?" }
            .take(6)

        val topTracks = stats.sortedByDescending { it.playCount }.take(8)

        val collected = linkedMapOf<String, Track>()
        coroutineScope {
            val jobs = buildList {
                // Related around most-played tracks
                topTracks.take(4).forEach { st ->
                    add(async { relatedFor(st) })
                }
                // Artist-flavored search fill
                topArtists.take(5).forEach { artist ->
                    add(async { searchFill("$artist mix", 8) })
                    add(async { searchFill("$artist songs", 6) })
                }
                // Cross-source variety from last artist
                topArtists.firstOrNull()?.let { a ->
                    add(async { searchFill(a, 10) })
                }
            }
            jobs.awaitAll().forEach { batch ->
                batch.forEach { t ->
                    if (t.id !in playedIds) collected.putIfAbsent(t.id, t)
                }
            }
        }

        val list = collected.values.toMutableList()
        Collections.shuffle(list)
        val items = list.take(limit).map { t ->
            BrowseItem.TrackItem(
                id = t.id,
                title = t.title,
                subtitle = t.artists.joinToString { it.name },
                coverUrl = t.coverUrl,
                track = t,
            )
        }

        val seedLabel = topArtists.take(2).joinToString(", ").ifBlank {
            topTracks.firstOrNull()?.title.orEmpty()
        }
        BrowseShelf(
            id = "de_xuat",
            title = "Đề xuất",
            kind = BrowseShelfKind.RECOMMENDATIONS,
            subtitle = if (seedLabel.isNotBlank()) {
                "Dựa trên lịch sử · $seedLabel"
            } else {
                "Dựa trên lịch sử nghe của bạn"
            },
            items = items,
        )
    }

    private suspend fun relatedFor(stat: TrackStats): List<Track> {
        val id = YouTubeMusicSourceClient.extractVideoId(stat.trackId) ?: return emptyList()
        return runCatching {
            newPipe.ensureInit()
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
            info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { si ->
                val url = si.url ?: return@mapNotNull null
                val vid = YouTubeMusicSourceClient.extractVideoId(url) ?: return@mapNotNull null
                Track(
                    id = vid,
                    title = si.name.orEmpty().ifBlank { return@mapNotNull null },
                    artists = listOf(
                        com.openwave.music.core.domain.Artist(
                            id = "yt-${si.uploaderName.hashCode()}",
                            name = si.uploaderName.orEmpty().ifBlank { "YouTube" },
                            source = MusicSource.YOUTUBE_MUSIC,
                        ),
                    ),
                    durationMs = if (si.duration > 0) si.duration * 1000 else 0L,
                    source = MusicSource.YOUTUBE_MUSIC,
                    coverUrl = com.openwave.music.core.domain.ArtworkUrls.highRes(
                        si.thumbnails?.maxByOrNull { it.height * it.width }?.url
                            ?: si.thumbnails?.firstOrNull()?.url,
                        vid,
                    ),
                    sourceUri = url,
                )
            }
        }.onFailure { Log.w(TAG, "related ${stat.trackId}: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private suspend fun searchFill(query: String, limit: Int): List<Track> {
        if (query.isBlank()) return emptyList()
        val out = linkedMapOf<String, Track>()
        for (src in listOf(MusicSource.YOUTUBE_MUSIC, MusicSource.SOUNDCLOUD)) {
            val client = clients.firstOrNull { it.source == src } ?: continue
            val hits = runCatching { client.search(query, limit) }
                .onFailure { Log.w(TAG, "search $src '$query': ${it.message}") }
                .getOrNull()
                ?.tracks
                .orEmpty()
            hits.forEach { out.putIfAbsent(it.id, it) }
        }
        return out.values.toList()
    }

    companion object {
        private const val TAG = "HistoryRecs"
    }
}
