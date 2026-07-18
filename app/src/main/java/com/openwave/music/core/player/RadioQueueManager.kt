package com.openwave.music.core.player

import android.util.Log
import com.openwave.music.core.domain.FastMusicCatalog
import com.openwave.music.core.domain.PlaybackState
import com.openwave.music.core.domain.Track
import com.openwave.music.features.OfflineRepository
import com.openwave.music.features.station.StationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto radio / station queue:
 * - When a track ends and there is no next item, generate a random related queue and continue.
 * - [startStation] builds a station from a seed and starts playback immediately.
 */
@Singleton
class RadioQueueManager @Inject constructor(
    private val player: PlayerController,
    private val catalog: FastMusicCatalog,
    private val stations: StationRepository,
    private val offline: OfflineRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var fillJob: Job? = null
    @Volatile private var started = false

    private val _autoContinue = MutableStateFlow(true)
    val autoContinue: StateFlow<Boolean> = _autoContinue.asStateFlow()

    private val _stationActive = MutableStateFlow(false)
    val stationActive: StateFlow<Boolean> = _stationActive.asStateFlow()

    private val _stationLabel = MutableStateFlow<String?>(null)
    val stationLabel: StateFlow<String?> = _stationLabel.asStateFlow()

    private val _isBuilding = MutableStateFlow(false)
    val isBuilding: StateFlow<Boolean> = _isBuilding.asStateFlow()

    private val playedIds = linkedSetOf<String>()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            player.snapshot
                .map { it.state to (it.track?.id ?: "") }
                .distinctUntilChanged()
                .collectLatest { (state, id) ->
                    if (id.isNotBlank()) {
                        playedIds += id
                        while (playedIds.size > MAX_PLAYED) {
                            playedIds.remove(playedIds.first())
                        }
                    }
                    if (state == PlaybackState.ENDED && _autoContinue.value) {
                        onTrackEndedNeedMore()
                    }
                }
        }
        // Prefill when nearing end of last item in queue
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(2_000)
                if (!_autoContinue.value) continue
                val snap = player.snapshot.value
                if (snap.state != PlaybackState.PLAYING) continue
                if (player.hasNext()) continue
                val progress = snap.progress
                if (progress.durationMs <= 0) continue
                val left = progress.durationMs - progress.positionMs
                if (left in 1..25_000L) {
                    val seed = snap.track ?: continue
                    ensureUpcoming(seed, playImmediately = false)
                }
            }
        }
    }

    fun setAutoContinue(enabled: Boolean) {
        _autoContinue.value = enabled
    }

    fun toggleAutoContinue() {
        _autoContinue.value = !_autoContinue.value
    }

    /**
     * Explicit "Station" action — like YT Music / SoundCloud station from a track.
     */
    fun startStation(seed: Track) {
        fillJob?.cancel()
        fillJob = scope.launch {
            mutex.withLock {
                _isBuilding.value = true
                _stationActive.value = true
                val artist = seed.artists.firstOrNull()?.name.orEmpty()
                _stationLabel.value = if (artist.isNotBlank()) {
                    "$artist station"
                } else {
                    "${seed.title} station"
                }
            }
            try {
                if (!player.awaitReady()) return@launch
                val related = stations.buildStation(seed, limit = STATION_SIZE)
                    .filter { it.id !in playedIds || it.id == seed.id }
                val queueTracks = buildList {
                    add(seed)
                    addAll(related.filter { it.id != seed.id })
                }
                val resolved = resolveMany(queueTracks, maxResolve = PRELOAD)
                if (resolved.isEmpty()) {
                    Log.w(TAG, "station empty for ${seed.id}")
                    return@launch
                }
                player.playResolved(
                    resolved,
                    startIndex = 0,
                    fullQueue = resolved.map { it.track },
                )
                if (queueTracks.size > resolved.size) {
                    val rest = queueTracks.drop(resolved.size)
                    appendResolved(rest, maxResolve = PRELOAD)
                }
                Log.i(TAG, "station started seed=${seed.id} queue=${queueTracks.size}")
            } catch (t: Throwable) {
                Log.e(TAG, "startStation: ${t.message}", t)
            } finally {
                _isBuilding.value = false
            }
        }
    }

    private suspend fun onTrackEndedNeedMore() {
        if (player.hasNext()) return
        val seed = player.snapshot.value.track ?: return
        ensureUpcoming(seed, playImmediately = true)
    }

    private suspend fun ensureUpcoming(seed: Track, playImmediately: Boolean) {
        if (!mutex.tryLock()) return
        try {
            if (player.hasNext() && !playImmediately) return
            _isBuilding.value = true
            val related = stations.buildStation(seed, limit = STATION_SIZE)
                .filter { it.id != seed.id && it.id !in playedIds }
            if (related.isEmpty()) {
                Log.w(TAG, "no related for auto-queue ${seed.id}")
                return
            }
            _stationActive.value = true
            if (_stationLabel.value == null) {
                val artist = seed.artists.firstOrNull()?.name.orEmpty()
                _stationLabel.value = if (artist.isNotBlank()) "$artist radio" else "Auto radio"
            }
            if (playImmediately && !player.hasNext()) {
                val resolved = resolveMany(related, maxResolve = PRELOAD)
                if (resolved.isEmpty()) return
                player.playResolved(resolved, 0, related)
                if (related.size > resolved.size) {
                    appendResolved(related.drop(resolved.size), PRELOAD)
                }
            } else {
                appendResolved(related, PRELOAD)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ensureUpcoming: ${t.message}", t)
        } finally {
            _isBuilding.value = false
            mutex.unlock()
        }
    }

    private suspend fun appendResolved(tracks: List<Track>, maxResolve: Int) {
        val resolved = resolveMany(tracks, maxResolve)
        if (resolved.isNotEmpty()) {
            player.appendResolved(resolved, fullQueueTail = resolved.map { it.track })
        }
    }

    private suspend fun resolveMany(
        tracks: List<Track>,
        maxResolve: Int,
    ): List<ResolvedStream> = coroutineScope {
        tracks.take(maxResolve).map { t ->
            async(Dispatchers.IO) {
                runCatching {
                    val local = offline.localStreamPath(t.id)
                    if (local != null) {
                        val url = if (local.startsWith("/")) "file://$local" else local
                        ResolvedStream(t, url)
                    } else {
                        val stream = catalog.resolveStreamFast(t)
                        ResolvedStream(t, stream.url, stream.headers)
                    }
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }

    companion object {
        private const val TAG = "RadioQueue"
        private const val STATION_SIZE = 24
        private const val PRELOAD = 6
        private const val MAX_PLAYED = 500
    }
}
