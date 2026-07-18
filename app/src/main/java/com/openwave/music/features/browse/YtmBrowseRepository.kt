package com.openwave.music.features.browse

import android.util.Log
import com.openwave.music.core.domain.BrowseItem
import com.openwave.music.core.domain.BrowseShelf
import com.openwave.music.core.domain.BrowseShelfKind
import com.openwave.music.core.domain.HomeFeed
import com.openwave.music.data.source.youtube.YouTubeChartsClient
import com.openwave.music.features.BrowseRepository
import com.openwave.music.features.home.HistoryRecommendationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Home feed:
 * 1. **Đề xuất** — from local play history ([HistoryRecommendationEngine])
 * 2. **Hàng đầu** — YouTube Charts daily (https://charts.youtube.com)
 *    - Bài hát hàng đầu
 *    - Nghệ sĩ hàng đầu
 */
@Singleton
class YtmBrowseRepository @Inject constructor(
    private val charts: YouTubeChartsClient,
    private val recommendations: HistoryRecommendationEngine,
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
            val chartsJob = async {
                runCatching { charts.dailyCharts(preferredRegion = preferredRegion(), limit = 30) }
                    .onFailure { Log.e(TAG, "charts: ${it.message}", it) }
                    .getOrNull()
            }

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
            val chartSubtitle = buildString {
                append("YouTube Charts")
                if (chart != null) append(" · ").append(chart.region.uppercase(Locale.US))
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
                chartDateLabel = dateLabel,
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

    private fun preferredRegion(): String {
        // Prefer device locale country; US often has daily song charts.
        val device = Locale.getDefault().country.lowercase(Locale.US)
        return device.ifBlank { "us" }
    }

    private fun formatViews(raw: String): String {
        val n = raw.toLongOrNull() ?: return raw
        return when {
            n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB lượt xem", n / 1_000_000_000.0)
            n >= 1_000_000 -> String.format(Locale.US, "%.1fM lượt xem", n / 1_000_000.0)
            n >= 1_000 -> String.format(Locale.US, "%.1fK lượt xem", n / 1_000.0)
            else -> "$n lượt xem"
        }
    }

    companion object {
        private const val TAG = "HomeBrowse"
        private const val CACHE_TTL_MS = 10 * 60_000L
    }
}
