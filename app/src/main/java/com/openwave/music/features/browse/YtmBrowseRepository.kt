package com.openwave.music.features.browse

import android.util.Log
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.BrowseItem
import com.openwave.music.core.domain.BrowseShelf
import com.openwave.music.core.domain.BrowseShelfKind
import com.openwave.music.core.domain.HomeFeed
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.Track
import com.openwave.music.data.source.IpRegionClient
import com.openwave.music.data.source.youtube.YouTubeChartsClient
import com.openwave.music.features.BrowseRepository
import com.openwave.music.features.LibraryRepository
import com.openwave.music.features.home.HistoryRecommendationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Home feed:
 * 1. **Nghe lại** — recent play history (hidden when empty)
 * 2. **Đề xuất** — from local play history ([HistoryRecommendationEngine])
 * 3. **Hàng đầu** — YouTube Charts for the user's IP region
 *    - Bài hát hàng đầu
 *    - Nghệ sĩ hàng đầu
 */
@Singleton
class YtmBrowseRepository @Inject constructor(
    private val charts: YouTubeChartsClient,
    private val recommendations: HistoryRecommendationEngine,
    private val library: LibraryRepository,
    private val ipRegion: IpRegionClient,
) : BrowseRepository {

    @Volatile
    private var cache: HomeFeed? = null
    private var cacheAt: Long = 0L

    override suspend fun homeShelves(): List<BrowseShelf> =
        homeFeed().shelves()

    suspend fun homeFeed(): HomeFeed = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache?.takeIf { now - cacheAt < CACHE_TTL_MS }?.let { return@withContext it }

        val feed = coroutineScope {
            val regionJob = async {
                runCatching { ipRegion.region() }
                    .onFailure { Log.w(TAG, "ip region: ${it.message}") }
                    .getOrNull()
            }
            val listenJob = async {
                runCatching { listenAgainShelf(20) }
                    .onFailure { Log.e(TAG, "listen again: ${it.message}", it) }
                    .getOrElse {
                        BrowseShelf(
                            id = "nghe_lai",
                            title = "Nghe lại",
                            kind = BrowseShelfKind.LISTEN_AGAIN,
                        )
                    }
            }
            val recsJob = async {
                runCatching { recommendations.recommendations(24) }
                    .onFailure { Log.e(TAG, "recs: ${it.message}", it) }
                    .getOrElse {
                        BrowseShelf(
                            id = "de_xuat",
                            title = "Đề xuất",
                            kind = BrowseShelfKind.RECOMMENDATIONS,
                            subtitle = "Không tải được đề xuất",
                        )
                    }
            }
            val region = regionJob.await()
            val regionCode = region?.countryCode ?: preferredRegion()
            val chartsJob = async {
                runCatching { charts.dailyCharts(preferredRegion = regionCode, limit = 30) }
                    .onFailure { Log.e(TAG, "charts: ${it.message}", it) }
                    .getOrNull()
            }

            val listenAgain = listenJob.await()
            val recs = recsJob.await()
            val chart = chartsJob.await()

            val dateLabel = YouTubeChartsClient.formatChartDate(
                chart?.songsEndDate ?: chart?.artistsEndDate,
            )
            val periodNote = when {
                chart?.songsPeriod?.contains("DAILY", true) == true -> "theo ngày"
                chart?.songsPeriod?.contains("WEEKLY", true) == true -> "theo tuần"
                else -> null
            }
            val regionLabel = regionDisplay(
                chartRegion = chart?.region,
                ipRegion = region,
            )
            val chartSubtitle = buildString {
                append("YouTube Charts")
                if (regionLabel != null) append(" · ").append(regionLabel)
                if (periodNote != null) append(" · ").append(periodNote)
                if (dateLabel != null) append(" · ").append(dateLabel)
            }

            val topSongs = BrowseShelf(
                id = "bai_hat_hang_dau",
                title = "Bài hát hàng đầu",
                kind = BrowseShelfKind.TOP_SONGS,
                subtitle = chartSubtitle,
                items = chart?.songs.orEmpty().map { s ->
                    BrowseItem.TrackItem(
                        id = s.track.id,
                        title = s.track.title,
                        subtitle = s.track.artists.joinToString { it.name },
                        coverUrl = s.track.coverUrl,
                        track = s.track,
                        rank = s.rank,
                        chartViews = s.viewCount,
                    )
                },
            )

            val topArtists = BrowseShelf(
                id = "nghe_si_hang_dau",
                title = "Nghệ sĩ hàng đầu",
                kind = BrowseShelfKind.TOP_ARTISTS,
                subtitle = chartSubtitle,
                items = chart?.artists.orEmpty().map { a ->
                    BrowseItem.ArtistItem(
                        id = a.artist.id,
                        title = a.artist.name,
                        subtitle = a.viewCount?.let { formatViews(it) },
                        coverUrl = a.coverUrl ?: a.artist.imageUrl,
                        artist = a.artist,
                        rank = a.rank,
                        channelId = a.channelId,
                    )
                },
            )

            HomeFeed(
                recommendations = recs,
                topSongs = topSongs,
                topArtists = topArtists,
                listenAgain = listenAgain,
                chartDateLabel = dateLabel,
                chartRegionLabel = regionLabel,
                isPartial = chart == null,
            )
        }

        cache = feed
        cacheAt = now
        feed
    }

    override suspend fun shelf(kind: BrowseShelfKind, params: String?): BrowseShelf {
        val feed = homeFeed()
        return when (kind) {
            BrowseShelfKind.LISTEN_AGAIN -> feed.listenAgain
            BrowseShelfKind.RECOMMENDATIONS -> feed.recommendations
            BrowseShelfKind.TOP_SONGS -> feed.topSongs
            BrowseShelfKind.TOP_ARTISTS -> feed.topArtists
            else -> feed.shelves().firstOrNull { it.kind == kind }
                ?: BrowseShelf(id = kind.name, title = kind.name, kind = kind)
        }
    }

    override fun invalidate() {
        cache = null
        cacheAt = 0L
    }

    private suspend fun listenAgainShelf(limit: Int): BrowseShelf {
        val plays = library.recentPlays(limit).first()
        val items = plays.map { play ->
            val source = play.source.takeIf { it != MusicSource.UNKNOWN }
                ?: MusicSource.YOUTUBE_MUSIC
            val track = Track(
                id = play.trackId,
                title = play.title,
                artists = listOf(
                    Artist(
                        id = "recent-${play.trackId}",
                        name = play.artist.ifBlank { "Unknown" },
                        source = source,
                    ),
                ),
                source = source,
                coverUrl = play.coverUrl,
            )
            BrowseItem.TrackItem(
                id = play.trackId,
                title = play.title,
                subtitle = play.artist,
                coverUrl = play.coverUrl,
                track = track,
            )
        }
        return BrowseShelf(
            id = "nghe_lai",
            title = "Nghe lại",
            kind = BrowseShelfKind.LISTEN_AGAIN,
            items = items,
        )
    }

    private fun preferredRegion(): String {
        val device = Locale.getDefault().country.lowercase(Locale.US)
        return device.ifBlank { "us" }
    }

    private fun regionDisplay(
        chartRegion: String?,
        ipRegion: IpRegionClient.Region?,
    ): String? {
        val code = (chartRegion ?: ipRegion?.countryCode)
            ?.uppercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val name = ipRegion?.countryName
            ?: runCatching {
                Locale("", code).displayCountry.takeIf { it.isNotBlank() }
            }.getOrNull()
        return when {
            name != null && !name.equals(code, ignoreCase = true) -> "$name ($code)"
            else -> code
        }
    }

    private fun formatViews(raw: String): String {
        val n = raw.toLongOrNull() ?: return "$raw total views"
        val amount = when {
            n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1_000_000_000.0)
            n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
            n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
            else -> n.toString()
        }
        return "$amount total views"
    }

    companion object {
        private const val TAG = "HomeBrowse"
        private const val CACHE_TTL_MS = 10 * 60_000L
    }
}
