package com.openwave.music.data.source

import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.MusicSourceClient
import com.openwave.music.core.domain.SearchResult
import com.openwave.music.core.domain.StreamInfo
import com.openwave.music.core.domain.Track
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube Music client — Phase 2 will wire InnerTube / NewPipeExtractor.
 * Debug builds fall back to [DemoCatalog] so UI is fully browsable.
 */
@Singleton
class YouTubeMusicSourceClient @Inject constructor(
    @Suppress("unused") private val http: OkHttpClient,
) : MusicSourceClient {

    override val source: MusicSource = MusicSource.YOUTUBE_MUSIC
    override val supportsAnonymousPlayback: Boolean = true

    override suspend fun search(query: String, limit: Int): SearchResult {
        val demo = DemoCatalog.search(query, limit)
        return SearchResult(
            tracks = demo.tracks.filter {
                it.source == MusicSource.YOUTUBE_MUSIC || it.source == MusicSource.LOCAL
            }.ifEmpty { demo.tracks },
        )
    }

    override suspend fun getTrack(id: String): Track? =
        DemoCatalog.tracks().firstOrNull { it.id == id && it.source == MusicSource.YOUTUBE_MUSIC }

    override suspend fun getStream(track: Track): StreamInfo? {
        track.streamUrl?.let { return StreamInfo(url = it, mimeType = "audio/mpeg", qualityLabel = "128kbps") }
        return DemoCatalog.tracks().firstOrNull { it.id == track.id }?.streamUrl?.let {
            StreamInfo(url = it, mimeType = "audio/mpeg", qualityLabel = "128kbps")
        }
    }
}

/**
 * SoundCloud client skeleton — demo data for debug preview.
 */
@Singleton
class SoundCloudSourceClient @Inject constructor(
    @Suppress("unused") private val http: OkHttpClient,
) : MusicSourceClient {

    override val source: MusicSource = MusicSource.SOUNDCLOUD
    override val supportsAnonymousPlayback: Boolean = true

    override suspend fun search(query: String, limit: Int): SearchResult {
        val demo = DemoCatalog.search(query, limit)
        return SearchResult(
            tracks = demo.tracks.filter { it.source == MusicSource.SOUNDCLOUD },
        )
    }

    override suspend fun getTrack(id: String): Track? =
        DemoCatalog.tracks().firstOrNull { it.id == id && it.source == MusicSource.SOUNDCLOUD }

    override suspend fun getStream(track: Track): StreamInfo? {
        track.streamUrl?.let { return StreamInfo(url = it, mimeType = "audio/mpeg", qualityLabel = "128kbps") }
        return DemoCatalog.tracks().firstOrNull { it.id == track.id }?.streamUrl?.let {
            StreamInfo(url = it, mimeType = "audio/mpeg", qualityLabel = "128kbps")
        }
    }
}

/**
 * Local / demo source always available for Play demo + offline-style hits.
 */
@Singleton
class DemoSourceClient @Inject constructor() : MusicSourceClient {

    override val source: MusicSource = MusicSource.LOCAL
    override val supportsAnonymousPlayback: Boolean = true

    override suspend fun search(query: String, limit: Int): SearchResult =
        DemoCatalog.search(query, limit)

    override suspend fun getTrack(id: String): Track? =
        DemoCatalog.tracks().firstOrNull { it.id == id }

    override suspend fun getStream(track: Track): StreamInfo? =
        track.streamUrl?.let { StreamInfo(url = it, mimeType = "audio/mpeg", qualityLabel = "128kbps") }
            ?: getTrack(track.id)?.streamUrl?.let {
                StreamInfo(url = it, mimeType = "audio/mpeg", qualityLabel = "128kbps")
            }
}
