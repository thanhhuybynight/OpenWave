package com.openwave.music.features.scrobble

import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.ScrobbleEntry
import com.openwave.music.core.domain.Track
import com.openwave.music.data.local.ScrobbleDao
import com.openwave.music.data.local.ScrobbleEntity
import com.openwave.music.features.ScrobbleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomScrobbleRepository @Inject constructor(
    private val dao: ScrobbleDao,
) : ScrobbleRepository {

    private val openStarts = mutableMapOf<String, Long>()

    override fun recent(limit: Int): Flow<List<ScrobbleEntry>> =
        dao.recent(limit).map { list ->
            list.map {
                ScrobbleEntry(
                    id = it.id,
                    trackId = it.trackId,
                    title = it.title,
                    artist = it.artist,
                    album = it.album,
                    source = runCatching { MusicSource.valueOf(it.source) }
                        .getOrDefault(MusicSource.UNKNOWN),
                    startedAtMs = it.startedAtMs,
                    listenedMs = it.listenedMs,
                    submitted = true,
                )
            }
        }

    override suspend fun onTrackStarted(track: Track) {
        openStarts[track.id] = System.currentTimeMillis()
    }

    override suspend fun onTrackProgress(track: Track, listenedMs: Long, durationMs: Long) = Unit

    override suspend fun onTrackEnded(track: Track, listenedMs: Long, durationMs: Long) {
        val threshold = maxOf(durationMs / 2, 4 * 60_000L)
        if (listenedMs < minOf(threshold, (durationMs * 0.5).toLong().coerceAtLeast(1))) {
            // still allow short tracks
            if (durationMs > 30_000L && listenedMs < durationMs * 0.4) return
        }
        val started = openStarts.remove(track.id) ?: (System.currentTimeMillis() - listenedMs)
        dao.insert(
            ScrobbleEntity(
                trackId = track.id,
                title = track.title,
                artist = track.artists.joinToString { it.name },
                album = track.album?.title,
                source = track.source.name,
                startedAtMs = started,
                listenedMs = listenedMs,
            ),
        )
    }
}
