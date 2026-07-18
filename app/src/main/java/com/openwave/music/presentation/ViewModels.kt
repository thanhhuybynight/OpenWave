package com.openwave.music.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwave.music.core.domain.CrossfadeSettings
import com.openwave.music.core.domain.FastMusicCatalog
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.PlayEvent
import com.openwave.music.core.domain.SearchBatch
import com.openwave.music.core.domain.SleepTimerState
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.UnifiedTrack
import com.openwave.music.core.domain.VoteStats
import com.openwave.music.core.player.PlaybackCoordinator
import com.openwave.music.core.player.PlayerController
import com.openwave.music.core.player.RadioQueueManager
import com.openwave.music.data.source.DemoSourceClient
import com.openwave.music.features.CrossfadeController
import com.openwave.music.features.LibraryRepository
import com.openwave.music.features.OfflineRepository
import com.openwave.music.features.ScrobbleRepository
import com.openwave.music.features.SleepTimer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val player: PlayerController,
    private val catalog: FastMusicCatalog,
    private val demo: DemoSourceClient,
    private val scrobble: ScrobbleRepository,
    private val library: LibraryRepository,
    private val offline: OfflineRepository,
    private val sleepTimer: SleepTimer,
    private val crossfade: CrossfadeController,
    private val radio: RadioQueueManager,
    coordinator: PlaybackCoordinator,
) : ViewModel() {

    val snapshot = player.snapshot
    val votes: StateFlow<VoteStats?> = coordinator.votes
    val sleepState: StateFlow<SleepTimerState> = sleepTimer.state
    val crossfadeSettings: StateFlow<CrossfadeSettings> = crossfade.settings
    val autoContinue: StateFlow<Boolean> = radio.autoContinue
    val stationActive: StateFlow<Boolean> = radio.stationActive
    val stationLabel: StateFlow<String?> = radio.stationLabel
    val stationBuilding: StateFlow<Boolean> = radio.isBuilding

    private val _playError = MutableStateFlow<String?>(null)
    val playError: StateFlow<String?> = _playError.asStateFlow()

    private val _isResolving = MutableStateFlow(false)
    val isResolving: StateFlow<Boolean> = _isResolving.asStateFlow()

    fun connect() = player.connect()

    fun clearPlayError() {
        _playError.value = null
    }

    /** Start a radio station from [seed] (YT Music / SoundCloud style). */
    fun startStation(seed: Track) {
        _playError.value = null
        radio.startStation(seed)
    }

    fun startStationFromCurrent() {
        val track = snapshot.value.track ?: return
        startStation(track)
    }

    fun toggleAutoContinue() = radio.toggleAutoContinue()

    fun setAutoContinue(enabled: Boolean) = radio.setAutoContinue(enabled)

    /** Start sleep timer for [durationMs] (custom duration from clock picker). */
    fun startSleepTimer(durationMs: Long) {
        if (durationMs <= 0L) return
        sleepTimer.start(durationMs)
    }

    fun cancelSleepTimer() = sleepTimer.cancel()

    fun setCrossfade(enabled: Boolean, durationSec: Int = 8) {
        crossfade.update(
            CrossfadeSettings(enabled = enabled, durationMs = durationSec * 1000),
        )
    }

    fun togglePlayPause() = player.togglePlayPause()

    fun seekTo(ms: Long) = player.seekTo(ms)

    fun skipNext() = player.skipNext()

    fun skipPrevious() = player.skipPrevious()

    fun toggleShuffle() {
        player.setShuffle(!snapshot.value.isShuffle)
    }

    fun cycleRepeat() = player.cycleRepeat()

    fun playDemo() {
        viewModelScope.launch {
            val track = demo.getTrack("demo-1") ?: return@launch
            playTrack(track)
        }
    }

    /** Play top search hit for an artist name (chart artist tap). */
    fun playArtist(artistName: String) {
        if (artistName.isBlank()) return
        viewModelScope.launch {
            _playError.value = null
            _isResolving.value = true
            try {
                val hit = catalog.searchProgressive(artistName, setOf(MusicSource.YOUTUBE_MUSIC))
                    .firstOrNull { it.tracks.isNotEmpty() }
                    ?.tracks
                    ?.firstOrNull()
                if (hit != null) {
                    playUnified(hit)
                } else {
                    _playError.value = "Không tìm thấy bài của $artistName"
                }
            } catch (t: Throwable) {
                _playError.value = t.message?.take(120) ?: "Artist play failed"
            } finally {
                _isResolving.value = false
            }
        }
    }

    fun playTrack(track: Track, alternates: List<Track> = emptyList()) {
        viewModelScope.launch {
            _playError.value = null
            _isResolving.value = true
            try {
                if (!player.awaitReady()) {
                    _playError.value = "Player not ready. Try again."
                    return@launch
                }
                val local = offline.localStreamPath(track.id)
                val url = if (local != null) {
                    if (local.startsWith("/")) "file://$local" else local
                } else {
                    catalog.resolveStreamFast(track, alternates).url
                }
                player.play(track, url, newQueue = listOf(track) + alternates)
                scrobble.onTrackStarted(track)
                library.recordPlay(
                    PlayEvent(
                        trackId = track.id,
                        title = track.title,
                        artist = track.artists.joinToString { it.name },
                        source = track.source,
                        playedAtMs = System.currentTimeMillis(),
                        durationMs = track.durationMs,
                        listenedMs = 0L,
                        completed = false,
                        coverUrl = track.coverUrl,
                    ),
                )
            } catch (t: Throwable) {
                _playError.value = t.message?.take(120) ?: "Could not play track"
            } finally {
                _isResolving.value = false
            }
        }
    }

    fun playUnified(hit: UnifiedTrack) {
        playTrack(hit.track, hit.alternates)
    }

    /** Append track to the end of the current Media3 queue (danh sách chờ). */
    fun enqueueTrack(track: Track, alternates: List<Track> = emptyList()) {
        viewModelScope.launch {
            _playError.value = null
            try {
                if (!player.awaitReady()) {
                    _playError.value = "Player not ready"
                    return@launch
                }
                // Nothing playing yet → start playback instead of orphan queue
                if (player.mediaItemCount() == 0 || snapshot.value.track == null) {
                    playTrack(track, alternates)
                    return@launch
                }
                val local = offline.localStreamPath(track.id)
                val url = if (local != null) {
                    if (local.startsWith("/")) "file://$local" else local
                } else {
                    catalog.resolveStreamFast(track, alternates).url
                }
                player.appendResolved(listOf(track to url), fullQueueTail = listOf(track))
            } catch (t: Throwable) {
                _playError.value = t.message?.take(120) ?: "Could not add to queue"
            }
        }
    }

    fun enqueueUnified(hit: UnifiedTrack) {
        enqueueTrack(hit.track, hit.alternates)
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            _playError.value = null
            _isResolving.value = true
            try {
                if (!player.awaitReady()) {
                    _playError.value = "Player not ready"
                    return@launch
                }
                val resolved = coroutineScope {
                    tracks.map { t ->
                        async {
                            val local = offline.localStreamPath(t.id)
                            val url = if (local != null) {
                                if (local.startsWith("/")) "file://$local" else local
                            } else {
                                catalog.resolveStreamFast(t).url
                            }
                            t to url
                        }
                    }.awaitAll()
                }
                player.playResolved(resolved, startIndex, tracks)
                tracks.getOrNull(startIndex)?.let { scrobble.onTrackStarted(it) }
            } catch (t: Throwable) {
                _playError.value = t.message?.take(120) ?: "Queue failed"
            } finally {
                _isResolving.value = false
            }
        }
    }

    fun prefetch(track: Track) {
        viewModelScope.launch {
            catalog.prefetchStream(track)
        }
    }

    fun download(track: Track) {
        viewModelScope.launch {
            offline.enqueue(track)
        }
    }
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalog: FastMusicCatalog,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _batch = MutableStateFlow(SearchBatch(query = ""))
    val batch: StateFlow<SearchBatch> = _batch.asStateFlow()

    /** null = all free sources */
    private val _sourceFilter = MutableStateFlow<Set<MusicSource>?>(null)
    val sourceFilter: StateFlow<Set<MusicSource>?> = _sourceFilter.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            // Re-run when either query or filter changes
            kotlinx.coroutines.flow.combine(
                _query.debounce(320).distinctUntilChanged(),
                _sourceFilter,
            ) { q, filter -> q to filter }
                .collectLatest { (q, filter) ->
                    searchJob?.cancel()
                    if (q.isBlank()) {
                        _batch.value = SearchBatch(query = q, isComplete = true)
                        return@collectLatest
                    }
                    searchJob = launch {
                        catalog.searchProgressive(q, filter).collect { _batch.value = it }
                    }
                }
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun setSourceFilter(sources: Set<MusicSource>?) {
        _sourceFilter.value = sources
    }

    fun toggleSource(source: MusicSource) {
        val cur = _sourceFilter.value
        _sourceFilter.value = when {
            cur == null -> setOf(source)
            source in cur && cur.size == 1 -> null // back to all
            source in cur -> (cur - source).ifEmpty { null }
            else -> cur + source
        }
    }
}
