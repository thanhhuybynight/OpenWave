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

@Singleton
class InMemoryLibraryRepository @Inject constructor() : LibraryRepository {

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    private val events = MutableStateFlow<List<PlayEvent>>(emptyList())

    override fun playlists(): Flow<List<LocalPlaylist>> = _playlists.asStateFlow()

    override suspend fun createPlaylist(title: String): LocalPlaylist {
        val p = LocalPlaylist(id = UUID.randomUUID().toString(), title = title)
        _playlists.update { it + p }
        return p
    }

    override suspend fun addToPlaylist(playlistId: String, track: Track) {
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
        _playlists.update { list ->
            list.map {
                if (it.id != playlistId) it
                else it.copy(trackIds = it.trackIds - trackId)
            }
        }
    }

    override suspend fun syncWithYtm(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Link YTM session in Phase 3 to enable sync"))

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
            )
        }.sortedByDescending { it.playCount }
    }

    override suspend fun recordPlay(event: PlayEvent) {
        events.update { listOf(event) + it }
    }
}
