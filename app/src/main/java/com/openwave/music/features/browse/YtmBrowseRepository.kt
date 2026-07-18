package com.openwave.music.features.browse

import com.openwave.music.core.domain.BrowseItem
import com.openwave.music.core.domain.BrowseShelf
import com.openwave.music.core.domain.BrowseShelfKind
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.Track
import com.openwave.music.data.source.DemoCatalog
import com.openwave.music.data.source.newpipe.NewPipeBootstrap
import com.openwave.music.data.source.youtube.YouTubeMusicSourceClient
import com.openwave.music.features.BrowseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Home shelves: try NewPipe kiosks (trending) + search-based moods; fall back to demo.
 */
@Singleton
class YtmBrowseRepository @Inject constructor(
    private val newPipe: NewPipeBootstrap,
    private val ytm: YouTubeMusicSourceClient,
) : BrowseRepository {

    @Volatile
    private var cache: List<BrowseShelf>? = null
    private var cacheAt: Long = 0L

    override suspend fun homeShelves(): List<BrowseShelf> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache?.takeIf { now - cacheAt < 5 * 60_000L }?.let { return@withContext it }

        newPipe.ensureInit()
        val shelves = runCatching { loadLive() }.getOrNull()
        val result = if (!shelves.isNullOrEmpty()) shelves else DemoCatalog.homeShelves()
        cache = result
        cacheAt = now
        result
    }

    override suspend fun shelf(kind: BrowseShelfKind, params: String?): BrowseShelf {
        return homeShelves().firstOrNull { it.kind == kind }
            ?: BrowseShelf(id = kind.name, title = kind.name, kind = kind)
    }

    override fun invalidate() {
        cache = null
        cacheAt = 0L
    }

    private suspend fun loadLive(): List<BrowseShelf> = coroutineScope {
        val trending = async { trendingTracks() }
        val focus = async { ytm.search("lofi focus music", 8).tracks }
        val energy = async { ytm.search("workout electronic", 8).tracks }
        val chill = async { ytm.search("chill r&b playlist", 8).tracks }

        val t = trending.await()
        val shelves = mutableListOf<BrowseShelf>()
        if (t.isNotEmpty()) {
            shelves += BrowseShelf(
                id = "home_quick",
                title = "Quick picks",
                kind = BrowseShelfKind.HOME_QUICK_PICKS,
                items = t.take(12).map { it.toItem() },
            )
            shelves += BrowseShelf(
                id = "charts",
                title = "Charts",
                kind = BrowseShelfKind.CHARTS,
                items = t.drop(2).take(12).map { it.toItem() },
            )
        }
        shelves += BrowseShelf(
            id = "moods",
            title = "Moods & genre",
            kind = BrowseShelfKind.MOODS_AND_GENRES,
            items = listOf(
                BrowseItem.CategoryItem("mood-focus", "Focus", "Deep work"),
                BrowseItem.CategoryItem("mood-energy", "Energy", "Move"),
                BrowseItem.CategoryItem("mood-chill", "Chill", "Wind down"),
            ) + chill.await().take(4).map { it.toItem() },
        )
        val podcastish = focus.await().take(6)
        if (podcastish.isNotEmpty()) {
            shelves += BrowseShelf(
                id = "podcasts",
                title = "Podcasts & long-form",
                kind = BrowseShelfKind.PODCASTS,
                items = podcastish.map { it.toItem() },
            )
        }
        val energyTracks = energy.await()
        if (energyTracks.isNotEmpty()) {
            shelves += BrowseShelf(
                id = "mixes",
                title = "Mixes",
                kind = BrowseShelfKind.MIXES,
                items = energyTracks.map { it.toItem() },
            )
        }
        shelves
    }

    private fun trendingTracks(): List<Track> = runCatching {
        val service = ServiceList.YouTube
        val kioskList = service.kioskList
        val id = kioskList.availableKiosks.firstOrNull() ?: return emptyList()
        val extractor = kioskList.getExtractorById(id, null)
        extractor.fetchPage()
        val info = KioskInfo.getInfo(extractor)
        info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { item ->
            val url = item.url ?: return@mapNotNull null
            val vid = YouTubeMusicSourceClient.extractVideoId(url) ?: return@mapNotNull null
            Track(
                id = vid,
                title = item.name.orEmpty(),
                artists = listOf(
                    com.openwave.music.core.domain.Artist(
                        id = item.uploaderName.orEmpty(),
                        name = item.uploaderName.orEmpty().ifBlank { "YouTube" },
                        source = MusicSource.YOUTUBE_MUSIC,
                    ),
                ),
                source = MusicSource.YOUTUBE_MUSIC,
                coverUrl = item.thumbnails?.firstOrNull()?.url,
                sourceUri = url,
            )
        }
    }.getOrDefault(emptyList())

    private fun Track.toItem() = BrowseItem.TrackItem(
        id = id,
        title = title,
        subtitle = artists.joinToString { it.name },
        coverUrl = coverUrl,
        track = this,
    )
}
