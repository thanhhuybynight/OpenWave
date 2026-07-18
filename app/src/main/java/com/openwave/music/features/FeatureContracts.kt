package com.openwave.music.features

import com.openwave.music.core.domain.AiSuggestion
import com.openwave.music.core.domain.BrowseShelf
import com.openwave.music.core.domain.BrowseShelfKind
import com.openwave.music.core.domain.CanvasMedia
import com.openwave.music.core.domain.CrossfadeSettings
import com.openwave.music.core.domain.LocalPlaylist
import com.openwave.music.core.domain.OfflineTrack
import com.openwave.music.core.domain.PlayEvent
import com.openwave.music.core.domain.QualityPreference
import com.openwave.music.core.domain.RecentPlay
import com.openwave.music.core.domain.SkipSegment
import com.openwave.music.core.domain.SleepTimerState
import com.openwave.music.core.domain.StreamInfo
import com.openwave.music.core.domain.StreamQuality
import com.openwave.music.core.domain.SubtitleCue
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.TrackStats
import com.openwave.music.core.domain.UserProfile
import com.openwave.music.core.domain.VideoStream
import com.openwave.music.core.domain.VoteStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface BrowseRepository {
    suspend fun homeShelves(): List<BrowseShelf>
    suspend fun shelf(kind: BrowseShelfKind, params: String? = null): BrowseShelf
    /** Invalidate short-TTL browse cache (pull-to-refresh). */
    fun invalidate()
}

interface StreamQualitySelector {
    var preference: QualityPreference
    /**
     * Pick best [StreamInfo] from candidate formats.
     * MAX only forces high itags when [QualityPreference.hasYtmPremiumSession].
     */
    fun select(candidates: List<StreamInfo>, preference: QualityPreference = this.preference): StreamInfo?
}

interface LibraryRepository {
    fun playlists(): Flow<List<LocalPlaylist>>
    fun playlistTracks(playlistId: String): Flow<List<Track>>
    suspend fun createPlaylist(title: String): LocalPlaylist
    suspend fun renamePlaylist(playlistId: String, title: String)
    suspend fun deletePlaylist(playlistId: String)
    suspend fun addToPlaylist(playlistId: String, track: Track)
    suspend fun removeFromPlaylist(playlistId: String, trackId: String)
    /** Optional: push/pull when YTM session present. */
    suspend fun syncWithYtm(): Result<Unit>
    fun stats(): Flow<List<TrackStats>>
    /** Unique tracks ordered by most recent listen first. */
    fun recentPlays(limit: Int = 50): Flow<List<RecentPlay>>
    suspend fun recordPlay(event: PlayEvent)
}

interface UserProfileRepository {
    val profile: Flow<UserProfile>
    suspend fun updateDisplayName(name: String)
    suspend fun updateAvatarUri(uri: String?)
}

interface OfflineRepository {
    fun downloads(): Flow<List<OfflineTrack>>
    suspend fun enqueue(track: Track, quality: StreamQuality = StreamQuality.HIGH)
    suspend fun remove(trackId: String)
    suspend fun localStreamPath(trackId: String): String?
}

interface ScrobbleRepository {
    fun recent(limit: Int = 50): Flow<List<com.openwave.music.core.domain.ScrobbleEntry>>
    suspend fun onTrackStarted(track: Track)
    suspend fun onTrackProgress(track: Track, listenedMs: Long, durationMs: Long)
    suspend fun onTrackEnded(track: Track, listenedMs: Long, durationMs: Long)
}

interface SponsorBlockClient {
    suspend fun segments(videoId: String): List<SkipSegment>
}

interface ReturnYoutubeDislikeClient {
    suspend fun votes(videoId: String): VoteStats?
}

interface CanvasRepository {
    suspend fun canvasFor(track: Track): CanvasMedia?
}

interface VideoRepository {
    suspend fun videoStreams(videoId: String): List<VideoStream>
    suspend fun subtitles(videoId: String): List<SubtitleCue>
}

interface AiSuggestionEngine {
    suspend fun suggest(seed: Track, limit: Int = 10): List<AiSuggestion>
    fun isConfigured(): Boolean
}

interface CrossfadeController {
    val settings: StateFlow<CrossfadeSettings>
    fun update(settings: CrossfadeSettings)
    /** Called by player when approaching end of current item. */
    fun onNearEnd(remainingMs: Long)
}

interface SleepTimer {
    val state: StateFlow<SleepTimerState>
    fun start(durationMs: Long)
    fun cancel()
}

interface ArtistNotificationScheduler {
    suspend fun refreshFollowed()
    fun schedulePeriodic()
}
