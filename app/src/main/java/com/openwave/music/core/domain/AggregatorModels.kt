package com.openwave.music.core.domain

/**
 * Product model: **one search → every free source → one tap play**.
 *
 * Speed rules:
 * 1. Search is parallel fan-out; slow sources never block the UI.
 * 2. First playable stream wins (race), with fallback chain.
 * 3. Stream URLs are cached with expiry; next tap is memory-hit.
 * 4. Prefetch stream when a row becomes visible / focused.
 */
data class UnifiedTrack(
    val track: Track,
    /** Same logical song may appear from multiple sources after dedupe. */
    val alternates: List<Track> = emptyList(),
    val relevanceScore: Float = 0f,
) {
    val primarySource: MusicSource get() = track.source
    val allSources: List<MusicSource>
        get() = (listOf(track) + alternates).map { it.source }.distinct()
}

/**
 * Progressive search: emit partial hits as each source finishes.
 * UI paints the first wave in ~100–300ms when a fast source responds.
 */
data class SearchBatch(
    val query: String,
    val tracks: List<UnifiedTrack> = emptyList(),
    /** Sources that already returned (success or empty). */
    val completedSources: Set<MusicSource> = emptySet(),
    /** Sources still in-flight. */
    val pendingSources: Set<MusicSource> = emptySet(),
    val isComplete: Boolean = false,
    val errorBySource: Map<MusicSource, String> = emptyMap(),
)

/** Latency / reliability knobs for the aggregator. */
data class AggregatorConfig(
    /** Hard cap per source search; late results dropped from this wave. */
    val searchTimeoutMs: Long = 2_500L,
    /** Soft budget for "first paint" — UI may show earlier via Flow. */
    val firstPaintBudgetMs: Long = 400L,
    val streamResolveTimeoutMs: Long = 4_000L,
    /** How long to keep resolved stream URLs in RAM. */
    val streamCacheTtlMs: Long = 25 * 60_000L,
    val maxResultsPerSource: Int = 15,
    /**
     * Prefer sources that historically resolve streams faster.
     * Lower index = higher priority when racing / ranking.
     */
    val sourcePriority: List<MusicSource> = listOf(
        MusicSource.YOUTUBE_MUSIC,
        MusicSource.SOUNDCLOUD,
        MusicSource.LOCAL,
        MusicSource.SPOTIFY_METADATA,
        MusicSource.APPLE_MUSIC_METADATA,
    ),
)

/**
 * Extended catalog for the multi-app hub product.
 */
interface FastMusicCatalog : MusicCatalog {
    val config: AggregatorConfig

    /** Progressive multi-source search for instant UI feedback. */
    fun searchProgressive(
        query: String,
        sources: Set<MusicSource>? = null,
    ): kotlinx.coroutines.flow.Flow<SearchBatch>

    /**
     * Resolve a playable URL ASAP.
     * Tries the track's own source first, then [UnifiedTrack.alternates] / same-title match.
     */
    suspend fun resolveStreamFast(track: Track, alternates: List<Track> = emptyList()): StreamInfo

    /** Warm the stream cache without playing (call when list item is visible). */
    suspend fun prefetchStream(track: Track)

    fun invalidateStreamCache(trackId: String? = null)
}
