package com.openwave.music.data.source.youtube

import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.MusicSourceClient
import com.openwave.music.core.domain.SearchResult
import com.openwave.music.core.domain.StreamInfo
import com.openwave.music.core.domain.Track
import com.openwave.music.data.source.DemoCatalog
import com.openwave.music.data.source.newpipe.NewPipeBootstrap
import com.openwave.music.features.StreamQualitySelector
import com.openwave.music.core.domain.QualityPreference
import com.openwave.music.core.domain.StreamQuality
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
 * YouTube / YouTube Music via NewPipeExtractor (anonymous, no login).
 * Falls back to [DemoCatalog] if extraction fails (offline / region / breakage).
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
            val live = runCatching { searchNewPipe(query, limit) }.getOrNull()
            if (live != null && live.tracks.isNotEmpty()) return@withContext live
            // Soft fallback so debug UI never goes empty on network issues
            val demo = DemoCatalog.search(query, limit)
            SearchResult(
                tracks = demo.tracks.filter {
                    it.source == MusicSource.YOUTUBE_MUSIC || it.source == MusicSource.LOCAL
                }.ifEmpty { demo.tracks },
            )
        }

    override suspend fun getTrack(id: String): Track? = withContext(Dispatchers.IO) {
        newPipe.ensureInit()
        runCatching {
            val info = NpStreamInfo.getInfo(watchUrl(id))
            info.toTrack()
        }.getOrNull()
            ?: DemoCatalog.tracks().firstOrNull { it.id == id }
    }

    override suspend fun getStream(track: Track): StreamInfo? = withContext(Dispatchers.IO) {
        // Prefer local demo URL if present (instant)
        if (track.source == MusicSource.LOCAL && !track.streamUrl.isNullOrBlank()) {
            return@withContext StreamInfo(
                url = track.streamUrl!!,
                mimeType = "audio/mpeg",
                qualityLabel = "128kbps",
            )
        }
        newPipe.ensureInit()
        val videoId = track.id.removePrefix("yt:").removePrefix("YTM:")
        runCatching {
            val info = NpStreamInfo.getInfo(watchUrl(videoId))
            pickAudio(info, qualitySelector.preference)?.let { audio ->
                StreamInfo(
                    url = audio.content,
                    mimeType = audio.format?.mimeType ?: "audio/webm",
                    qualityLabel = "${audio.averageBitrate}kbps",
                    expiresAtEpochMs = System.currentTimeMillis() + 5 * 60 * 60_000L,
                )
            }
        }.getOrNull()
            ?: track.streamUrl?.let {
                StreamInfo(url = it, mimeType = "audio/mpeg", qualityLabel = "128kbps")
            }
            ?: DemoCatalog.tracks().firstOrNull { it.id == track.id }?.streamUrl?.let {
                StreamInfo(url = it, mimeType = "audio/mpeg", qualityLabel = "128kbps")
            }
    }

    private fun searchNewPipe(query: String, limit: Int): SearchResult {
        val service = ServiceList.YouTube
        val handler = service.searchQHFactory.fromQuery(query)
        val info = SearchInfo.getInfo(service, handler)
        val tracks = info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val url = item.url ?: return@mapNotNull null
                val id = extractVideoId(url) ?: return@mapNotNull null
                Track(
                    id = id,
                    title = item.name.orEmpty(),
                    artists = listOf(
                        Artist(
                            id = "yt-uploader-${item.uploaderName}",
                            name = item.uploaderName.orEmpty().ifBlank { "YouTube" },
                            source = MusicSource.YOUTUBE_MUSIC,
                        ),
                    ),
                    durationMs = item.duration * 1000,
                    source = MusicSource.YOUTUBE_MUSIC,
                    coverUrl = item.thumbnails?.firstOrNull()?.url,
                    sourceUri = url,
                )
            }
            .take(limit)
        return SearchResult(tracks = tracks)
    }

    private fun pickAudio(
        info: NpStreamInfo,
        pref: QualityPreference,
    ): AudioStream? {
        val candidates = info.audioStreams.orEmpty()
        if (candidates.isEmpty()) return null
        val mapped = candidates.map { a ->
            StreamInfo(
                url = a.content,
                mimeType = a.format?.mimeType,
                qualityLabel = "${a.averageBitrate}kbps",
            ) to a
        }
        val chosen = qualitySelector.select(
            mapped.map { it.first },
            pref.copy(
                preferred = when {
                    pref.hasYtmPremiumSession && pref.preferred == StreamQuality.MAX -> StreamQuality.MAX
                    pref.preferred == StreamQuality.HIGH -> StreamQuality.HIGH
                    else -> StreamQuality.AUTO
                },
            ),
        )
        return mapped.firstOrNull { it.first.url == chosen?.url }?.second
            ?: candidates.maxByOrNull { it.averageBitrate }
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
        durationMs = duration * 1000,
        source = MusicSource.YOUTUBE_MUSIC,
        coverUrl = thumbnails?.firstOrNull()?.url,
        sourceUri = url,
    )

    companion object {
        fun watchUrl(videoId: String): String =
            "https://www.youtube.com/watch?v=${videoId.removePrefix("yt:")}"

        fun extractVideoId(url: String): String? {
            val patterns = listOf(
                Regex("(?:v=|/shorts/|youtu\\.be/|\\?v=)([\\w-]{11})"),
                Regex("^([\\w-]{11})$"),
            )
            for (p in patterns) {
                p.find(url)?.groupValues?.getOrNull(1)?.let { return it }
            }
            return null
        }
    }
}
