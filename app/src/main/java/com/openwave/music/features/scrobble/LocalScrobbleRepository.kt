package com.openwave.music.features.scrobble

import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.ScrobbleEntry
import com.openwave.music.core.domain.Track
import com.openwave.music.features.ScrobbleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicLong

/**
 * Local Last.fm-style scrobble log (in-memory; swap to Room in Phase 2C persistence).
 * Scrobble rule (classic): listened ≥ 50% or ≥ 4 minutes.
 */
@Singleton
class LocalScrobbleRepository @Inject constructor() : ScrobbleRepository {

    private val idSeq = AtomicLong(1)
    private val entries = MutableStateFlow<List<ScrobbleEntry>>(emptyList())
    private val openStarts = mutableMapOf<String, Long>()

    override fun recent(limit: Int): Flow<List<ScrobbleEntry>> =
        entries.map { it.take(limit) }

    override suspend fun onTrackStarted(track: Track) {
        openStarts[track.id] = System.currentTimeMillis()
    }

    override suspend fun onTrackProgress(track: Track, listenedMs: Long, durationMs: Long) {
        // Optional: live UI progress; scrobble committed on end
    }

    override suspend fun onTrackEnded(track: Track, listenedMs: Long, durationMs: Long) {
        val threshold = maxOf(durationMs / 2, 4 * 60_000L)
        if (listenedMs < threshold && listenedMs < durationMs * 0.5) return
        val started = openStarts.remove(track.id) ?: (System.currentTimeMillis() - listenedMs)
        val entry = ScrobbleEntry(
            id = idSeq.getAndIncrement(),
            trackId = track.id,
            title = track.title,
            artist = track.artists.joinToString { it.name },
            album = track.album?.title,
            source = track.source,
            startedAtMs = started,
            listenedMs = listenedMs,
            submitted = true, // local always "submitted"
        )
        entries.update { listOf(entry) + it }
    }

    fun clear() {
        entries.value = emptyList()
        openStarts.clear()
    }
}
