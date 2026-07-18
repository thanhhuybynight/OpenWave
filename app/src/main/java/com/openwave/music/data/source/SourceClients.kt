package com.openwave.music.data.source

import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.MusicSourceClient
import com.openwave.music.core.domain.SearchResult
import com.openwave.music.core.domain.StreamInfo
import com.openwave.music.core.domain.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local / demo source — always available for offline-friendly smoke tests.
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

// YouTubeMusicSourceClient → data/source/youtube/
// SoundCloudSourceClient → data/source/soundcloud/
