package com.openwave.music.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwave.music.core.domain.FastMusicCatalog
import com.openwave.music.core.domain.PlayEvent
import com.openwave.music.core.domain.SearchBatch
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.UnifiedTrack
import com.openwave.music.core.player.PlayerController
import com.openwave.music.data.source.DemoSourceClient
import com.openwave.music.features.LibraryRepository
import com.openwave.music.features.ScrobbleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val player: PlayerController,
    private val catalog: FastMusicCatalog,
    private val demo: DemoSourceClient,
    private val scrobble: ScrobbleRepository,
    private val library: LibraryRepository,
) : ViewModel() {

    val snapshot = player.snapshot

    fun connect() = player.connect()

    fun togglePlayPause() = player.togglePlayPause()

    fun seekTo(ms: Long) = player.seekTo(ms)

    fun skipNext() = player.skipNext()

    fun skipPrevious() = player.skipPrevious()

    fun playDemo() {
        viewModelScope.launch {
            val track = demo.getTrack("demo-1") ?: return@launch
            playTrack(track)
        }
    }

    /**
     * Tap-to-play path optimized for latency:
     * cache → resolve race → ExoPlayer prepare/play.
     * Also feeds local scrobble + play stats.
     */
    fun playTrack(track: Track, alternates: List<Track> = emptyList()) {
        viewModelScope.launch {
            runCatching {
                val stream = catalog.resolveStreamFast(track, alternates)
                player.play(track, stream.url)
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
                    ),
                )
            }
        }
    }

    fun playUnified(hit: UnifiedTrack) {
        playTrack(hit.track, hit.alternates)
    }

    /** Warm cache while user scrolls (call from list visibility). */
    fun prefetch(track: Track) {
        viewModelScope.launch {
            catalog.prefetchStream(track)
        }
    }

    override fun onCleared() {
        super.onCleared()
    }
}

/**
 * Search hub: one query → progressive multi-source results.
 * Debounce keeps typing snappy; progressive batches paint early.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalog: FastMusicCatalog,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _batch = MutableStateFlow(SearchBatch(query = ""))
    val batch: StateFlow<SearchBatch> = _batch.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            _query
                .debounce(280)
                .distinctUntilChanged()
                .collectLatest { q ->
                    searchJob?.cancel()
                    if (q.isBlank()) {
                        _batch.value = SearchBatch(query = q, isComplete = true)
                        return@collectLatest
                    }
                    searchJob = launch {
                        catalog.searchProgressive(q).collect { _batch.value = it }
                    }
                }
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }
}
