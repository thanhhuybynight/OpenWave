package com.openwave.music.core.domain

/**
 * Source-agnostic catalog models.
 * Extractors map platform payloads into these types — player never knows
 * whether audio came from YouTube Music, SoundCloud, or a local file.
 */
enum class MusicSource {
    YOUTUBE_MUSIC,
    SOUNDCLOUD,
    SPOTIFY_METADATA, // metadata only; see SourcePolicy
    APPLE_MUSIC_METADATA, // metadata only; see SourcePolicy
    LOCAL,
    UNKNOWN,
}

data class Artist(
    val id: String,
    val name: String,
    val source: MusicSource,
    val imageUrl: String? = null,
)

data class Album(
    val id: String,
    val title: String,
    val artists: List<Artist>,
    val source: MusicSource,
    val coverUrl: String? = null,
    val year: Int? = null,
    /** Epoch ms of release (or best approximation) for newest-first sorting. */
    val releasedAtMs: Long? = null,
    val sourceUri: String? = null,
    val trackCount: Long? = null,
)

/** Full artist page payload (YTM). */
data class ArtistPage(
    val artist: Artist,
    val channelId: String? = null,
    val description: String? = null,
    val subscriberCount: Long? = null,
    /** Top songs by listen count (max 5). */
    val highlights: List<Track> = emptyList(),
    /** Albums newest release first. */
    val albums: List<Album> = emptyList(),
)

/**
 * A playable (or discoverable) track in the unified catalog.
 * [streamUrl] may be null until resolved by [StreamResolver] just-in-time.
 */
data class Track(
    val id: String,
    val title: String,
    val artists: List<Artist>,
    val album: Album? = null,
    val durationMs: Long = 0L,
    val source: MusicSource,
    val coverUrl: String? = null,
    val sourceUri: String? = null,
    val streamUrl: String? = null,
    val isExplicit: Boolean = false,
)

data class Playlist(
    val id: String,
    val title: String,
    val description: String? = null,
    val coverUrl: String? = null,
    val tracks: List<Track> = emptyList(),
    val source: MusicSource,
)

data class SearchResult(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)

/** Playback position / progress for UI. */
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
) {
    val fraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

enum class PlaybackState {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR,
}

data class PlayerSnapshot(
    val track: Track? = null,
    val state: PlaybackState = PlaybackState.IDLE,
    val progress: PlaybackProgress = PlaybackProgress(),
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val isShuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val errorMessage: String? = null,
)

enum class RepeatMode { OFF, ONE, ALL }
