package com.openwave.music.core.domain

/**
 * Contracts every music source must implement.
 *
 * Legal note (read MASTER_PLAN.md):
 * - YouTube Music / SoundCloud: FOSS extractors with public web endpoints (fragile, ToS gray).
 * - Spotify / Apple Music: DRM-protected catalogs. OpenWave only allows metadata adapters
 *   or user-licensed official SDKs — never FairPlay/Widevine circumvention.
 */
interface MusicSourceClient {
    val source: MusicSource

    suspend fun search(query: String, limit: Int = 20): SearchResult

    suspend fun getTrack(id: String): Track?

    suspend fun getStream(track: Track): StreamInfo?

    /** Whether this client can produce a playable URL without user login. */
    val supportsAnonymousPlayback: Boolean
}

data class StreamInfo(
    val url: String,
    val mimeType: String? = null,
    val expiresAtEpochMs: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val qualityLabel: String? = null,
)

/**
 * Aggregates multiple [MusicSourceClient]s — search fans out, play routes to owner source.
 */
interface MusicCatalog {
    suspend fun search(query: String, sources: Set<MusicSource>? = null): SearchResult
    suspend fun resolveStream(track: Track): StreamInfo
}

/**
 * Policy gates for sources that cannot legally stream anonymously.
 */
object SourcePolicy {
    fun canStreamAnonymously(source: MusicSource): Boolean = when (source) {
        MusicSource.YOUTUBE_MUSIC,
        MusicSource.SOUNDCLOUD,
        MusicSource.LOCAL,
        -> true

        MusicSource.SPOTIFY_METADATA,
        MusicSource.APPLE_MUSIC_METADATA,
        MusicSource.UNKNOWN,
        -> false
    }

    fun displayName(source: MusicSource): String = when (source) {
        MusicSource.YOUTUBE_MUSIC -> "YouTube Music"
        MusicSource.SOUNDCLOUD -> "SoundCloud"
        MusicSource.SPOTIFY_METADATA -> "Spotify (metadata)"
        MusicSource.APPLE_MUSIC_METADATA -> "Apple Music (metadata)"
        MusicSource.LOCAL -> "Local"
        MusicSource.UNKNOWN -> "Unknown"
    }
}
