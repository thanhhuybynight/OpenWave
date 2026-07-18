package com.openwave.music.features.stubs

import com.openwave.music.core.domain.AiSuggestion
import com.openwave.music.core.domain.CanvasMedia
import com.openwave.music.core.domain.SubtitleCue
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.VideoStream
import com.openwave.music.features.AiSuggestionEngine
import com.openwave.music.features.ArtistNotificationScheduler
import com.openwave.music.features.CanvasRepository
import com.openwave.music.features.VideoRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StubCanvasRepository @Inject constructor() : CanvasRepository {
    override suspend fun canvasFor(track: Track): CanvasMedia? = null
}

@Singleton
class StubVideoRepository @Inject constructor() : VideoRepository {
    override suspend fun videoStreams(videoId: String): List<VideoStream> = emptyList()
    override suspend fun subtitles(videoId: String): List<SubtitleCue> = emptyList()
}

@Singleton
class StubAiSuggestionEngine @Inject constructor() : AiSuggestionEngine {
    override suspend fun suggest(seed: Track, limit: Int): List<AiSuggestion> = emptyList()
    override fun isConfigured(): Boolean = false
}

@Singleton
class StubArtistNotificationScheduler @Inject constructor() : ArtistNotificationScheduler {
    override suspend fun refreshFollowed() = Unit
    override fun schedulePeriodic() = Unit
}
