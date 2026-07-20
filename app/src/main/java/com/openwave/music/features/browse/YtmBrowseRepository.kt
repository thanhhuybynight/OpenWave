package com.openwave.music.features.browse

import android.util.Log
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.BrowseItem
import com.openwave.music.core.domain.BrowseShelf
import com.openwave.music.core.domain.BrowseShelfKind
import com.openwave.music.core.domain.HomeFeed
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.MusicSourceClient
import com.openwave.music.core.domain.Track
import com.openwave.music.data.source.IpRegionClient
import com.openwave.music.data.source.youtube.YouTubeChartsClient
import com.openwave.music.data.source.youtube.YtmAccountHomeClient
import com.openwave.music.features.settings.YouTubeSessionStore
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
    private val accountHome: YtmAccountHomeClient,
    private val session: YouTubeSessionStore,
    private val clients: Set<@JvmSuppressWildcards MusicSourceClient>,
) : BrowseRepository {

    @Volatile
    private var cache: HomeFeed? = null
    private var cacheAt: Long = 0L
    private var homeContinuation: String? = null
    private val seenTrackIds = hashSetOf<String>()
    private val seenTrackKeys = hashSetOf<String>()
    private var searchCursor = 0
    private var searchRound = 0
    private var fallbackSeeds: List<Track> = emptyList()
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
                val accountTracks = if (session.loggedIn.first()) {
                    runCatching {
                        accountHome.account()
                        accountHome.recommendations(limit = 24).also {
                            homeContinuation = it.continuation
                        }.tracks.let { diversify(it) }
                    }
                        .onFailure { Log.e(TAG, "account home: ${it.message}", it) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                if (accountTracks.isNotEmpty()) {
                    BrowseShelf(
                        id = "de_xuat_youtube",
                        title = "Dành cho bạn",
                        kind = BrowseShelfKind.RECOMMENDATIONS,
                        subtitle = "Đồng bộ từ YouTube Music",
                        items = accountTracks.map { track ->
                            BrowseItem.TrackItem(
                                id = track.id,
                                title = track.title,
                                subtitle = track.artists.joinToString { it.name },
                                coverUrl = track.coverUrl,
                                track = track,
                            )
                        },
                    )
                } else {
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

        val recommendationTracks = feed.recommendations.items
            .filterIsInstance<BrowseItem.TrackItem>()
            .map { it.track }
        fallbackSeeds = (recommendationTracks + feed.listenAgain.items
            .filterIsInstance<BrowseItem.TrackItem>()
            .map { it.track })
            .distinctBy(::trackKey)
        recommendationTracks.forEach(::markSeen)
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

    suspend fun loadMoreRecommendations(): List<Track> = withContext(Dispatchers.IO) {
        val continued = homeContinuation?.let { continuation ->
            runCatching { accountHome.recommendations(continuation) }
                .onFailure { Log.w(TAG, "home continuation: ${it.message}") }
                .getOrNull()
                ?.also { homeContinuation = it.continuation }
                ?.tracks
                .orEmpty()
        }.orEmpty()
        val fresh = unseen(diversify(continued))
        if (fresh.isNotEmpty()) return@withContext fresh

        repeat(MAX_FALLBACK_QUERIES) {
            val query = nextFallbackQuery() ?: return@repeat
            val hits = searchBoth(query, FALLBACK_RESULTS_PER_SOURCE)
            val batch = unseen(hits)
            if (batch.isNotEmpty()) return@withContext batch
        }
        emptyList()
    }

    private suspend fun searchBoth(query: String, limit: Int): List<Track> = coroutineScope {
        clients.filter { it.source == MusicSource.YOUTUBE_MUSIC || it.source == MusicSource.SOUNDCLOUD }
            .map { client ->
                async {
                    runCatching { client.search(query, limit).tracks }
                        .onFailure { Log.w(TAG, "${client.source} '$query': ${it.message}") }
                        .getOrDefault(emptyList())
                }
            }.map { it.await() }.let(::interleave)
    }

    private fun nextFallbackQuery(): String? {
        if (fallbackSeeds.isEmpty()) return null
        val seed = fallbackSeeds[searchCursor % fallbackSeeds.size]
        val artist = seed.artists.firstOrNull()?.name.orEmpty().trim()
        val title = seed.title.trim()
        val variant = searchRound % QUERY_VARIANTS
        val query = when (variant) {
            0 -> listOf(artist, title).filter(String::isNotBlank).joinToString(" ")
            1 -> "$artist mix".trim()
            2 -> "$title related".trim()
            else -> "$artist $title radio".trim()
        }
        searchCursor++
        if (searchCursor % fallbackSeeds.size == 0) searchRound++
        return query.takeIf(String::isNotBlank)
    }

    private fun unseen(tracks: List<Track>): List<Track> {
        val fresh = tracks.filter { track ->
            val id = "${track.source}:${track.id}"
            val key = trackKey(track)
            id !in seenTrackIds && key !in seenTrackKeys && markSeen(track)
        }
        if (fresh.isNotEmpty()) {
            fallbackSeeds = (fallbackSeeds + fresh).distinctBy(::trackKey).takeLast(MAX_FALLBACK_SEEDS)
        }
        return fresh
    }

    private fun markSeen(track: Track): Boolean {
        seenTrackIds += "${track.source}:${track.id}"
        seenTrackKeys += trackKey(track)
        return true
    }

    private fun trackKey(track: Track): String = buildString {
        append(normalize(track.title))
        append('|')
        append(normalize(track.artists.firstOrNull()?.name.orEmpty()))
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.US).filter(Char::isLetterOrDigit)

    private fun interleave(groups: List<List<Track>>): List<Track> = buildList {
        repeat(groups.maxOfOrNull { it.size } ?: 0) { index ->
            groups.forEach { group -> group.getOrNull(index)?.let(::add) }
        }
    }

    private suspend fun diversify(youtube: List<Track>): List<Track> {
        if (youtube.isEmpty()) return emptyList()
        val soundCloud = clients.firstOrNull { it.source == MusicSource.SOUNDCLOUD }
        val seeds = youtube.asSequence().map { track ->
            "${track.artists.firstOrNull()?.name.orEmpty()} ${track.title}".trim()
        }.filter(String::isNotBlank).distinct().take(4).toList()
        val soundCloudTracks = coroutineScope {
            seeds.map { query ->
                async {
                    runCatching { soundCloud?.search(query, 3)?.tracks.orEmpty() }
                        .onFailure { Log.w(TAG, "SoundCloud '$query': ${it.message}") }
                        .getOrDefault(emptyList())
                }
            }.map { it.await() }.flatten()
        }
        val seen = hashSetOf<String>()
        fun key(track: Track) = buildString {
            append(track.title.lowercase(Locale.US).filter(Char::isLetterOrDigit))
            append('|')
            append(track.artists.firstOrNull()?.name.orEmpty().lowercase(Locale.US).filter(Char::isLetterOrDigit))
        }
        val yt = youtube.filter { seen.add(key(it)) }
        val sc = soundCloudTracks.filter { seen.add(key(it)) }
        return buildList {
            val size = maxOf(yt.size, sc.size)
            repeat(size) { index ->
                yt.getOrNull(index)?.let(::add)
                sc.getOrNull(index)?.let(::add)
            }
        }
    }

    override fun invalidate() {
        cache = null
        cacheAt = 0L
        homeContinuation = null
        seenTrackIds.clear()
        seenTrackKeys.clear()
        searchCursor = 0
        searchRound = 0
        fallbackSeeds = emptyList()
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
        private const val MAX_FALLBACK_QUERIES = 4
        private const val MAX_FALLBACK_SEEDS = 200
        private const val FALLBACK_RESULTS_PER_SOURCE = 8
        private const val QUERY_VARIANTS = 4
    }
}
