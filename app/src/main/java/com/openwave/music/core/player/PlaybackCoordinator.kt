package com.openwave.music.core.player

import com.openwave.music.core.domain.PlaybackState
import com.openwave.music.core.domain.PlayEvent
import com.openwave.music.core.domain.SkipSegment
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.VoteStats
import com.openwave.music.data.source.youtube.YouTubeMusicSourceClient
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Side-effects around playback: scrobble, SponsorBlock skip, RYD votes.
 */
@Singleton
class PlaybackCoordinator @Inject constructor(
    private val player: PlayerController,
    private val scrobble: ScrobbleRepository,
    private val library: LibraryRepository,
    private val sponsorBlock: SponsorBlockClient,
    private val ryd: ReturnYoutubeDislikeClient,
    private val radio: RadioQueueManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null
    private var segments: List<SkipSegment> = emptyList()
    private var listenStartMs: Long = 0L
    private var accumulatedListenMs: Long = 0L
    private var lastTrackId: String? = null

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
        // finalize previous
        lastTrackId?.let { prev ->
            if (accumulatedListenMs > 0) {
                // no track object; skip
            }
        }
        lastTrackId = track.id
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
                segments = runCatching { sponsorBlock.segments(videoId) }.getOrDefault(emptyList())
                _segments.value = segments
            }
        }
    }

    private suspend fun onEnded(track: Track) {
        val listened = accumulatedListenMs +
            (System.currentTimeMillis() - listenStartMs).coerceAtLeast(0L)
        scrobble.onTrackEnded(track, listened, track.durationMs.coerceAtLeast(listened))
        library.recordPlay(
            PlayEvent(
                trackId = track.id,
                title = track.title,
                artist = com.openwave.music.core.domain.ArtistNameSplitter
                    .encodeFromArtists(track.artists)
                    .ifBlank { track.artists.joinToString { it.name } },
                source = track.source,
                playedAtMs = System.currentTimeMillis(),
                durationMs = track.durationMs,
                listenedMs = listened,
                completed = true,
                coverUrl = track.coverUrl,
            ),
        )
        accumulatedListenMs = 0L
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
                    // SponsorBlock auto-skip
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
}
