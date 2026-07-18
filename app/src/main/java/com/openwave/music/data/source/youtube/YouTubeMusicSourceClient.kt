package com.openwave.music.data.source.youtube

import android.util.Log
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.MusicSourceClient
import com.openwave.music.core.domain.QualityPreference
import com.openwave.music.core.domain.SearchResult
import com.openwave.music.core.domain.StreamInfo
import com.openwave.music.core.domain.StreamQuality
import com.openwave.music.core.domain.Track
import com.openwave.music.data.source.DemoCatalog
import com.openwave.music.data.source.newpipe.NewPipeBootstrap
import com.openwave.music.data.source.newpipe.NewPipeDownloader
import com.openwave.music.features.StreamQualitySelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo as NpStreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube / YouTube Music via NewPipeExtractor (anonymous).
 * Search tries music filters first, then general videos.
 */
@Singleton
class YouTubeMusicSourceClient @Inject constructor(
    private val newPipe: NewPipeBootstrap,
    private val qualitySelector: StreamQualitySelector,
) : MusicSourceClient {

    override val source: MusicSource = MusicSource.YOUTUBE_MUSIC
    override val supportsAnonymousPlayback: Boolean = true

    override suspend fun search(query: String, limit: Int): SearchResult =
        withContext(Dispatchers.IO) {
            newPipe.ensureInit()
            val q = query.trim()
            if (q.isEmpty()) return@withContext SearchResult()

            val live = runCatching { searchNewPipe(q, limit) }
                .onFailure { Log.w(TAG, "YT search failed: ${it.message}") }
                .getOrNull()

            if (live != null && live.tracks.isNotEmpty()) return@withContext live

            // Soft demo fallback only when query matches demo titles
            val demo = DemoCatalog.search(q, limit)
            SearchResult(
                tracks = demo.tracks.filter {
                    it.source == MusicSource.YOUTUBE_MUSIC || it.source == MusicSource.LOCAL
                },
            )
        }

    override suspend fun getTrack(id: String): Track? = withContext(Dispatchers.IO) {
        newPipe.ensureInit()
        val videoId = normalizeId(id)
        runCatching {
            NpStreamInfo.getInfo(watchUrl(videoId)).toTrack()
        }.getOrNull()
            ?: DemoCatalog.tracks().firstOrNull { it.id == id || it.id == videoId }
    }

    override suspend fun getStream(track: Track): StreamInfo? = withContext(Dispatchers.IO) {
        if (track.source == MusicSource.LOCAL && !track.streamUrl.isNullOrBlank()) {
            return@withContext StreamInfo(
                url = track.streamUrl!!,
                mimeType = "audio/mpeg",
                qualityLabel = "128kbps",
            )
        }
        newPipe.ensureInit()
        val videoId = normalizeId(track.id)
        runCatching {
            val info = NpStreamInfo.getInfo(watchUrl(videoId))
            val audio = pickAudio(info, qualitySelector.preference)
                ?: error("no audio streams for $videoId")
            val url = audio.content ?: error("empty stream url")
            StreamInfo(
                url = url,
                mimeType = audio.format?.mimeType ?: "audio/webm",
                qualityLabel = "${audio.averageBitrate}kbps",
                expiresAtEpochMs = System.currentTimeMillis() + 4 * 60 * 60_000L,
                headers = mapOf(
                    "Referer" to "https://www.youtube.com",
                    "User-Agent" to NewPipeDownloader.USER_AGENT,
                ),
            )
        }.onFailure { Log.w(TAG, "YT stream failed for $videoId: ${it.message}") }
            .getOrNull()
            ?: track.streamUrl?.takeIf { it.startsWith("http") }?.let {
                StreamInfo(url = it, mimeType = "audio/mpeg", qualityLabel = "128kbps")
            }
    }

    private fun searchNewPipe(query: String, limit: Int): SearchResult {
        val service = ServiceList.YouTube
        val factory = service.searchQHFactory
        val availableFilters = factory.availableContentFilter?.toList().orEmpty()

        // Prefer music-oriented filters when NewPipe exposes them
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
                val durationSec = item.duration
                // Skip live-only / unknown ultra-long shells when duration is 0 and name empty
                val title = item.name.orEmpty()
                if (title.isBlank()) return@forEach
                tracks[id] = Track(
                    id = id,
                    title = title,
                    artists = listOf(
                        Artist(
                            id = "yt-${item.uploaderName.hashCode()}",
                            name = item.uploaderName.orEmpty().ifBlank { "YouTube" },
                            source = MusicSource.YOUTUBE_MUSIC,
                        ),
                    ),
                    durationMs = if (durationSec > 0) durationSec * 1000 else 0L,
                    source = MusicSource.YOUTUBE_MUSIC,
                    coverUrl = item.thumbnails
                        ?.maxByOrNull { (it.height * it.width) }
                        ?.url
                        ?: item.thumbnails?.firstOrNull()?.url,
                    sourceUri = url,
                )
            }
            if (tracks.size >= limit) break
        }

        // If still empty, plain query once more
        if (tracks.isEmpty()) {
            val info = SearchInfo.getInfo(service, factory.fromQuery(query))
            info.relatedItems.filterIsInstance<StreamInfoItem>().forEach { item ->
                val url = item.url ?: return@forEach
                val id = extractVideoId(url) ?: return@forEach
                tracks[id] = Track(
                    id = id,
                    title = item.name.orEmpty(),
                    artists = listOf(
                        Artist(
                            id = "yt-${item.uploaderName}",
                            name = item.uploaderName.orEmpty().ifBlank { "YouTube" },
                            source = MusicSource.YOUTUBE_MUSIC,
                        ),
                    ),
                    durationMs = if (item.duration > 0) item.duration * 1000 else 0L,
                    source = MusicSource.YOUTUBE_MUSIC,
                    coverUrl = item.thumbnails?.firstOrNull()?.url,
                    sourceUri = url,
                )
            }
        }

        return SearchResult(tracks = tracks.values.take(limit).toList())
    }

    private fun pickAudio(info: NpStreamInfo, pref: QualityPreference): AudioStream? {
        val candidates = info.audioStreams.orEmpty()
            .filter { !it.content.isNullOrBlank() }
        if (candidates.isEmpty()) return null

        // Prefer progressive / non-manifest when possible; NewPipe already gives direct URLs
        val ranked = candidates.sortedByDescending { it.averageBitrate }
        val target = when (pref.preferred) {
            StreamQuality.MAX -> if (pref.hasYtmPremiumSession) 256 else 160
            StreamQuality.HIGH -> 128
            StreamQuality.AUTO -> 128
        }
        return ranked.firstOrNull { it.averageBitrate in (target - 40)..(target + 80) }
            ?: ranked.firstOrNull { it.averageBitrate >= 96 }
            ?: ranked.first()
    }

    private fun NpStreamInfo.toTrack(): Track = Track(
        id = extractVideoId(url) ?: id.toString(),
        title = name.orEmpty(),
        artists = listOf(
            Artist(
                id = uploaderUrl.orEmpty(),
                name = uploaderName.orEmpty().ifBlank { "YouTube" },
                source = MusicSource.YOUTUBE_MUSIC,
            ),
        ),
        durationMs = if (duration > 0) duration * 1000 else 0L,
        source = MusicSource.YOUTUBE_MUSIC,
        coverUrl = thumbnails?.firstOrNull()?.url,
        sourceUri = url,
    )

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
