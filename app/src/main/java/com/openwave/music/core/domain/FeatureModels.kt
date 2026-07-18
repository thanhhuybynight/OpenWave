package com.openwave.music.core.domain

/**
 * Extended domain for SimpMusic-parity features.
 * Keep player/catalog free of UI; features plug in via interfaces.
 */

// ── Stream quality (YTM Premium optional) ───────────────────────────────────

enum class StreamQuality {
    /** Best effort without Premium session. */
    AUTO,
    /** Prefer ≥128 kbps when available. */
    HIGH,
    /** Prefer max (≤256 kbps audio) — requires YTM Premium session when applicable. */
    MAX,
}

data class QualityPreference(
    val preferred: StreamQuality = StreamQuality.AUTO,
    /** Set when user optionally links YTM (cookie / tokens). Never required for guest play. */
    val hasYtmPremiumSession: Boolean = false,
    val hasYtmSession: Boolean = false,
)

// ── Browse (Home, Charts, Podcasts, Moods & Genre) ──────────────────────────

enum class BrowseShelfKind {
    HOME_QUICK_PICKS,
    CHARTS,
    PODCASTS,
    MOODS_AND_GENRES,
    NEW_RELEASES,
    MIXES,
    CUSTOM,
}

data class BrowseShelf(
    val id: String,
    val title: String,
    val kind: BrowseShelfKind,
    val items: List<BrowseItem> = emptyList(),
)

sealed class BrowseItem {
    abstract val id: String
    abstract val title: String
    abstract val subtitle: String?
    abstract val coverUrl: String?

    data class TrackItem(
        override val id: String,
        override val title: String,
        override val subtitle: String? = null,
        override val coverUrl: String? = null,
        val track: Track,
    ) : BrowseItem()

    data class PlaylistItem(
        override val id: String,
        override val title: String,
        override val subtitle: String? = null,
        override val coverUrl: String? = null,
        val playlist: Playlist,
    ) : BrowseItem()

    data class CategoryItem(
        override val id: String,
        override val title: String,
        override val subtitle: String? = null,
        override val coverUrl: String? = null,
        val params: String? = null,
    ) : BrowseItem()
}

// ── Library / playlists / stats ─────────────────────────────────────────────

data class LocalPlaylist(
    val id: String,
    val title: String,
    val description: String? = null,
    val trackIds: List<String> = emptyList(),
    val coverUrl: String? = null,
    val remoteYtmPlaylistId: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

data class PlayEvent(
    val trackId: String,
    val title: String,
    val artist: String,
    val source: MusicSource,
    val playedAtMs: Long,
    val durationMs: Long,
    val listenedMs: Long,
    val completed: Boolean,
)

data class TrackStats(
    val trackId: String,
    val title: String,
    val artist: String,
    val playCount: Int,
    val totalListenedMs: Long,
    val lastPlayedAtMs: Long,
)

// ── Scrobble ────────────────────────────────────────────────────────────────

data class ScrobbleEntry(
    val id: Long = 0,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val source: MusicSource,
    val startedAtMs: Long,
    val listenedMs: Long,
    val submitted: Boolean = false,
)

// ── Offline ─────────────────────────────────────────────────────────────────

enum class DownloadState { QUEUED, DOWNLOADING, COMPLETED, FAILED, PAUSED }

data class OfflineTrack(
    val trackId: String,
    val track: Track,
    val localPath: String,
    val bytes: Long,
    val quality: StreamQuality,
    val state: DownloadState,
    val downloadedAtMs: Long = System.currentTimeMillis(),
)

// ── SponsorBlock / RYD ──────────────────────────────────────────────────────

data class SkipSegment(
    val category: String,
    val startMs: Long,
    val endMs: Long,
)

data class VoteStats(
    val videoId: String,
    val likes: Long,
    val dislikes: Long,
    val rating: Double? = null,
)

// ── Canvas / video / AI ─────────────────────────────────────────────────────

data class CanvasMedia(
    val trackId: String,
    val loopVideoUrl: String?,
    val canvasUrl: String?,
)

data class VideoStream(
    val videoId: String,
    val url: String,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
)

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class AiSuggestion(
    val track: Track,
    val reason: String,
)

// ── Crossfade / sleep ───────────────────────────────────────────────────────

data class CrossfadeSettings(
    val enabled: Boolean = false,
    val durationMs: Int = 8_000,
)

data class SleepTimerState(
    val active: Boolean = false,
    val endsAtEpochMs: Long? = null,
    val remainingMs: Long = 0L,
)

// ── Artist notifications ────────────────────────────────────────────────────

data class FollowedArtist(
    val id: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val lastNotifiedReleaseId: String? = null,
)
