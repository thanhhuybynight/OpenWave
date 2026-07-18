package com.openwave.music.data.source.youtube

import android.util.Log
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.ArtistNameSplitter
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.MusicSourceClient
import com.openwave.music.core.domain.SearchResult
import com.openwave.music.core.domain.StreamInfo
import com.openwave.music.core.domain.Track
import com.openwave.music.data.source.newpipe.NewPipeBootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo as NpStreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube Music source.
 * - Search: NewPipe (music filters → videos)
 * - Stream: SimpMusic pipeline via [YtmStreamResolver]
 *   (InnerTube WEB_REMIX player + NewPipe itag map)
 */
@Singleton
class YouTubeMusicSourceClient @Inject constructor(
    private val newPipe: NewPipeBootstrap,
    private val streamResolver: YtmStreamResolver,
    private val credits: YtmCreditsClient,
) : MusicSourceClient {

    override val source: MusicSource = MusicSource.YOUTUBE_MUSIC
    override val supportsAnonymousPlayback: Boolean = true

    override suspend fun search(query: String, limit: Int): SearchResult =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext SearchResult()

            // Prefer InnerTube YTM search — full multi-artist credits with browseIds
            val ytm = runCatching { credits.searchSongs(q, limit) }
                .onFailure { Log.w(TAG, "YTM search failed: ${it.message}") }
                .getOrNull()
            if (!ytm.isNullOrEmpty()) return@withContext SearchResult(tracks = ytm)

            newPipe.ensureInit()
            val live = runCatching { searchNewPipe(q, limit) }
                .onFailure { Log.w(TAG, "YT search failed: ${it.message}") }
                .getOrNull()

            // Never fall back to DemoCatalog — empty is honest when extractors fail
            live ?: SearchResult()
        }

    override suspend fun getTrack(id: String): Track? = withContext(Dispatchers.IO) {
        val videoId = normalizeId(id)
        // Enrich performers via YTM /next (multi-artist + browseId)
        val credited = runCatching { credits.artistsForVideo(videoId) }.getOrDefault(emptyList())
        newPipe.ensureInit()
        val base = runCatching {
            NpStreamInfo.getInfo(watchUrl(videoId)).toTrack()
        }.getOrNull()
        when {
            base != null && credited.isNotEmpty() -> base.copy(artists = credited)
            base != null -> base
            credited.isNotEmpty() -> Track(
                id = videoId,
                title = credited.joinToString(" & ") { it.name },
                artists = credited,
                source = MusicSource.YOUTUBE_MUSIC,
                sourceUri = watchUrl(videoId),
            )
            else -> null
        }
    }

    override suspend fun getStream(track: Track): StreamInfo? = withContext(Dispatchers.IO) {
        if (track.source == MusicSource.LOCAL && !track.streamUrl.isNullOrBlank()) {
            return@withContext StreamInfo(
                url = track.streamUrl!!,
                mimeType = "audio/mpeg",
                qualityLabel = "128kbps",
            )
        }
        // Don't treat googlevideo-less demo/watch URLs as already resolved
        if (!track.streamUrl.isNullOrBlank() &&
            track.streamUrl!!.contains("googlevideo.com")
        ) {
            return@withContext StreamInfo(
                url = track.streamUrl!!,
                mimeType = "audio/mp4",
                qualityLabel = "cached",
            )
        }
        val videoId = normalizeId(track.id)
        try {
            streamResolver.resolveAudio(videoId)
        } catch (t: Throwable) {
            Log.e(TAG, "YT stream failed for $videoId: ${t.message}", t)
            throw t
        }
    }

    private fun searchNewPipe(query: String, limit: Int): SearchResult {
        val service = ServiceList.YouTube
        val factory = service.searchQHFactory
        val availableFilters = factory.availableContentFilter?.toList().orEmpty()

        val preferredFilters = listOf(
            listOf("music_songs"),
            listOf("music_videos"),
            listOf("videos"),
            emptyList(),
        )

        val tracks = linkedMapOf<String, Track>()
        for (filter in preferredFilters) {
            if (filter.isNotEmpty() && availableFilters.isNotEmpty() &&
                filter.any { it !in availableFilters }
            ) {
                continue
            }
            val handler = runCatching {
                if (filter.isEmpty()) factory.fromQuery(query)
                else factory.fromQuery(query, filter, "")
            }.getOrNull() ?: continue

            val info = runCatching { SearchInfo.getInfo(service, handler) }
                .onFailure { Log.w(TAG, "search filter $filter: ${it.message}") }
                .getOrNull() ?: continue

            info.relatedItems.filterIsInstance<StreamInfoItem>().forEach { item ->
                val url = item.url ?: return@forEach
                val id = extractVideoId(url) ?: return@forEach
                if (tracks.containsKey(id)) return@forEach
                val title = item.name.orEmpty()
                if (title.isBlank()) return@forEach
                val durationSec = item.duration
                tracks[id] = Track(
                    id = id,
                    title = title,
                    artists = artistsFromCredits(item.uploaderName, title),
                    durationMs = if (durationSec > 0) durationSec * 1000 else 0L,
                    source = MusicSource.YOUTUBE_MUSIC,
                    coverUrl = item.thumbnails
                        ?.maxByOrNull { it.height * it.width }
                        ?.url
                        ?: item.thumbnails?.firstOrNull()?.url,
                    sourceUri = url,
                )
            }
            if (tracks.size >= limit) break
        }

        if (tracks.isEmpty()) {
            val info = SearchInfo.getInfo(service, factory.fromQuery(query))
            info.relatedItems.filterIsInstance<StreamInfoItem>().forEach { item ->
                val url = item.url ?: return@forEach
                val id = extractVideoId(url) ?: return@forEach
                val title = item.name.orEmpty()
                tracks[id] = Track(
                    id = id,
                    title = title,
                    artists = artistsFromCredits(item.uploaderName, title),
                    durationMs = if (item.duration > 0) item.duration * 1000 else 0L,
                    source = MusicSource.YOUTUBE_MUSIC,
                    coverUrl = item.thumbnails?.firstOrNull()?.url,
                    sourceUri = url,
                )
            }
        }

        return SearchResult(tracks = tracks.values.take(limit).toList())
    }

    private fun NpStreamInfo.toTrack(): Track = Track(
        id = extractVideoId(url) ?: id.toString(),
        title = name.orEmpty(),
        artists = artistsFromCredits(uploaderName, name.orEmpty()),
        durationMs = if (duration > 0) duration * 1000 else 0L,
        source = MusicSource.YOUTUBE_MUSIC,
        coverUrl = thumbnails?.firstOrNull()?.url,
        sourceUri = url,
    )

    /**
     * Build separate [Artist] entries from uploader + title credits
     * (e.g. "Bruno Mars, ROSÉ", "Artist feat. Guest").
     */
    private fun artistsFromCredits(uploader: String?, title: String): List<Artist> {
        val names = linkedSetOf<String>()
        ArtistNameSplitter.split(uploader.orEmpty()).forEach { names += it }
        // feat./with in title
        Regex(
            """\((?:feat\.?|ft\.?|with)\s+([^)]+)\)""",
            RegexOption.IGNORE_CASE,
        ).find(title)?.groupValues?.getOrNull(1)?.let { credit ->
            ArtistNameSplitter.split(credit).forEach { names += it }
        }
        Regex(
            """\s+(?:feat\.?|ft\.?|with)\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        ).find(title)?.groupValues?.getOrNull(1)?.let { credit ->
            ArtistNameSplitter.split(credit.removeSuffix(")").trim()).forEach { names += it }
        }
        if (names.isEmpty()) {
            val fallback = uploader.orEmpty().ifBlank { "YouTube" }
            names += fallback
        }
        return names.map { n ->
            Artist(
                id = "yt-${n.hashCode()}",
                name = n,
                source = MusicSource.YOUTUBE_MUSIC,
            )
        }
    }

    companion object {
        private const val TAG = "YtmSource"

        fun watchUrl(videoId: String): String =
            "https://www.youtube.com/watch?v=${normalizeId(videoId)}"

        fun normalizeId(id: String): String =
            id.removePrefix("yt:").removePrefix("YTM:").trim()

        fun extractVideoId(url: String): String? {
            val patterns = listOf(
                Regex("(?:v=|/shorts/|youtu\\.be/|/embed/|\\?v=)([\\w-]{11})"),
                Regex("^([\\w-]{11})$"),
            )
            for (p in patterns) {
                p.find(url)?.groupValues?.getOrNull(1)?.let { return it }
            }
            return null
        }
    }
}
