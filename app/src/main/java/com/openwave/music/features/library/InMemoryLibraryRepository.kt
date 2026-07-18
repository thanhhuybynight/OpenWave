package com.openwave.music.features.library

import com.openwave.music.core.domain.LocalPlaylist
import com.openwave.music.core.domain.PlayEvent
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.TrackStats
import com.openwave.music.features.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Test/fallback library (not bound in DI; Room is primary). */
@Singleton
class InMemoryLibraryRepository @Inject constructor() : LibraryRepository {

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    private val tracks = MutableStateFlow<Map<String, List<Track>>>(emptyMap())
    private val events = MutableStateFlow<List<PlayEvent>>(emptyList())

    override fun playlists(): Flow<List<LocalPlaylist>> = _playlists.asStateFlow()

    override fun playlistTracks(playlistId: String): Flow<List<Track>> =
        tracks.map { it[playlistId].orEmpty() }

    override suspend fun createPlaylist(title: String): LocalPlaylist {
        val p = LocalPlaylist(id = UUID.randomUUID().toString(), title = title)
        _playlists.update { it + p }
        return p
    }

    override suspend fun renamePlaylist(playlistId: String, title: String) {
        _playlists.update { list ->
            list.map { if (it.id == playlistId) it.copy(title = title) else it }
        }
    }

    override suspend fun deletePlaylist(playlistId: String) {
        _playlists.update { it.filterNot { p -> p.id == playlistId } }
        tracks.update { it - playlistId }
    }

    override suspend fun addToPlaylist(playlistId: String, track: Track) {
        tracks.update { map ->
            val cur = map[playlistId].orEmpty()
            map + (playlistId to (cur + track).distinctBy { it.id })
        }
        _playlists.update { list ->
            list.map {
                if (it.id != playlistId) it
                else it.copy(
                    trackIds = (it.trackIds + track.id).distinct(),
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
        }
    }

    override suspend fun removeFromPlaylist(playlistId: String, trackId: String) {
        tracks.update { map ->
            map + (playlistId to map[playlistId].orEmpty().filterNot { it.id == trackId })
        }
    }

    override suspend fun syncWithYtm(): Result<Unit> =
        Result.failure(UnsupportedOperationException("memory backend"))

    override fun stats(): Flow<List<TrackStats>> = events.map { list ->
        list.groupBy { it.trackId }.map { (id, plays) ->
            val first = plays.first()
            TrackStats(
                trackId = id,
                title = first.title,
                artist = first.artist,
                playCount = plays.size,
                totalListenedMs = plays.sumOf { it.listenedMs },
                lastPlayedAtMs = plays.maxOf { it.playedAtMs },
                coverUrl = first.coverUrl,
                source = first.source,
            )
        }.sortedByDescending { it.playCount }
    }

    override fun recentPlays(limit: Int) = events.map { list ->
        list.groupBy { it.trackId }
            .map { (id, plays) ->
                val last = plays.maxBy { it.playedAtMs }
                com.openwave.music.core.domain.RecentPlay(
                    trackId = id,
                    title = last.title,
                    artist = last.artist,
                    source = last.source,
                    lastPlayedAtMs = last.playedAtMs,
                    coverUrl = last.coverUrl,
                )
            }
            .sortedByDescending { it.lastPlayedAtMs }
            .take(limit)
    }

    override suspend fun recordPlay(event: PlayEvent) {
        events.update { listOf(event) + it }
    }
}
