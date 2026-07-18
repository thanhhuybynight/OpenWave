package com.openwave.music.core.player

import com.openwave.music.core.domain.ArtistNameSplitter
import com.openwave.music.core.domain.PlayEvent
import com.openwave.music.core.domain.PlaybackState
import com.openwave.music.core.domain.SkipSegment
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.VoteStats
import com.openwave.music.data.source.youtube.YouTubeMusicSourceClient
import com.openwave.music.data.source.youtube.YtmCreditsClient
import com.openwave.music.features.LibraryRepository
import com.openwave.music.features.ReturnYoutubeDislikeClient
import com.openwave.music.features.ScrobbleRepository
import com.openwave.music.features.SponsorBlockClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Side-effects around playback: scrobble, history, SponsorBlock skip, RYD votes.
 * History is written once per listen (on end or skip), never at play start.
 */
@Singleton
class PlaybackCoordinator @Inject constructor(
    private val player: PlayerController,
    private val scrobble: ScrobbleRepository,
    private val library: LibraryRepository,
    private val sponsorBlock: SponsorBlockClient,
    private val ryd: ReturnYoutubeDislikeClient,
    private val radio: RadioQueueManager,
    private val ytmCredits: YtmCreditsClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null
    private var segments: List<SkipSegment> = emptyList()
    private var listenStartMs: Long = 0L
    private var accumulatedListenMs: Long = 0L
    private var lastTrackId: String? = null
    private var lastTrack: Track? = null
    private var finalizedForId: String? = null

    private val _votes = MutableStateFlow<VoteStats?>(null)
    val votes: StateFlow<VoteStats?> = _votes.asStateFlow()

    private val _segments = MutableStateFlow<List<SkipSegment>>(emptyList())
    val skipSegments: StateFlow<List<SkipSegment>> = _segments.asStateFlow()

    fun start() {
        radio.start()
        scope.launch {
            player.snapshot
                .map { it.track?.id to it.state }
                .distinctUntilChanged()
                .collectLatest { (id, state) ->
                    val track = player.snapshot.value.track
                    if (track != null && id != lastTrackId) {
                        onNewTrack(track)
                    }
                    if (state == PlaybackState.ENDED && track != null) {
                        onEnded(track)
                    }
                    if (state == PlaybackState.PLAYING) {
                        ensureTicker()
                    }
                }
        }
    }

    private suspend fun onNewTrack(track: Track) {
        // Finalize previous listen when user skips / changes track (not double-count ENDED)
        lastTrack?.let { prev ->
            if (prev.id != track.id && finalizedForId != prev.id && accumulatedListenMs > 0L) {
                finalizePlay(prev, completed = false)
            }
        }
        lastTrackId = track.id
        lastTrack = track
        finalizedForId = null
        listenStartMs = System.currentTimeMillis()
        accumulatedListenMs = 0L
        scrobble.onTrackStarted(track)
        _votes.value = null
        segments = emptyList()
        _segments.value = emptyList()

        val videoId = YouTubeMusicSourceClient.extractVideoId(track.id)
            ?: YouTubeMusicSourceClient.extractVideoId(track.sourceUri.orEmpty())
            ?: track.id.takeIf { it.length == 11 }

        if (videoId != null) {
            scope.launch(Dispatchers.IO) {
                _votes.value = runCatching { ryd.votes(videoId) }.getOrNull()
                // Music-safe categories only (no intro/outro/music_offtopic that cut songs)
                segments = runCatching { sponsorBlock.segments(videoId) }.getOrDefault(emptyList())
                    .filter { it.category in SKIP_CATEGORIES }
                _segments.value = segments
            }
        }
    }

    private suspend fun onEnded(track: Track) {
        finalizePlay(track, completed = true)
        accumulatedListenMs = 0L
    }

    private suspend fun finalizePlay(track: Track, completed: Boolean) {
        if (finalizedForId == track.id) return
        finalizedForId = track.id
        val wall = (System.currentTimeMillis() - listenStartMs).coerceAtLeast(0L)
        val cap = track.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val effective = maxOf(accumulatedListenMs, wall).coerceAtMost(cap)
        scrobble.onTrackEnded(track, effective, track.durationMs.coerceAtLeast(effective))
        // Skip history for accidental <3s taps (still scrobble-notified above)
        if (effective < MIN_HISTORY_MS && !completed) return

        val credited = withContext(Dispatchers.IO) {
            val videoId = YouTubeMusicSourceClient.extractVideoId(track.id)
                ?: YouTubeMusicSourceClient.extractVideoId(track.sourceUri.orEmpty())
            if (videoId != null && track.artists.count { it.id.startsWith("UC") } < 2) {
                val artists = runCatching { ytmCredits.artistsForVideo(videoId) }
                    .getOrDefault(emptyList())
                if (artists.isNotEmpty()) track.copy(artists = artists) else track
            } else {
                track
            }
        }
        library.recordPlay(
            PlayEvent(
                trackId = credited.id,
                title = credited.title,
                artist = ArtistNameSplitter.encodeFromArtists(credited.artists)
                    .ifBlank { credited.artists.joinToString { it.name } },
                source = credited.source,
                playedAtMs = System.currentTimeMillis(),
                durationMs = credited.durationMs,
                listenedMs = effective,
                completed = completed ||
                    (credited.durationMs > 0 && effective >= credited.durationMs * 0.9),
                coverUrl = credited.coverUrl,
            ),
        )
    }

    private fun ensureTicker() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            var lastPos = 0L
            while (isActive) {
                val snap = player.snapshot.value
                if (snap.state == PlaybackState.PLAYING) {
                    val pos = snap.progress.positionMs
                    if (pos >= lastPos) {
                        accumulatedListenMs += (pos - lastPos).coerceAtMost(2_000L)
                    }
                    lastPos = pos
                    val hit = segments.firstOrNull { pos in it.startMs until it.endMs }
                    if (hit != null) {
                        player.seekTo(hit.endMs + 50)
                        lastPos = hit.endMs
                    }
                }
                delay(500L)
            }
        }
    }

    companion object {
        private const val MIN_HISTORY_MS = 3_000L
        private val SKIP_CATEGORIES = setOf(
            "sponsor",
            "selfpromo",
            "interaction",
            "exclusive_access",
        )
    }
}
