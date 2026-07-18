package com.openwave.music.features.library

import com.openwave.music.core.domain.LocalPlaylist
import com.openwave.music.core.domain.PlayEvent
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.TrackStats
import com.openwave.music.data.local.PlayEventDao
import com.openwave.music.data.local.PlayEventEntity
import com.openwave.music.data.local.PlaylistDao
import com.openwave.music.data.local.PlaylistEntity
import com.openwave.music.data.local.PlaylistTrackEntity
import com.openwave.music.features.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomLibraryRepository @Inject constructor(
    private val playlists: PlaylistDao,
    private val events: PlayEventDao,
) : LibraryRepository {

    override fun playlists(): Flow<List<LocalPlaylist>> =
        playlists.all().map { list ->
            list.map {
                LocalPlaylist(
                    id = it.id,
                    title = it.title,
                    description = it.description,
                    coverUrl = it.coverUrl,
                    remoteYtmPlaylistId = it.remoteYtmPlaylistId,
                    updatedAtMs = it.updatedAtMs,
                    trackIds = emptyList(),
                )
            }
        }

    override suspend fun createPlaylist(title: String): LocalPlaylist {
        val p = LocalPlaylist(id = UUID.randomUUID().toString(), title = title)
        playlists.upsert(
            PlaylistEntity(
                id = p.id,
                title = p.title,
                description = null,
                coverUrl = null,
                remoteYtmPlaylistId = null,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        return p
    }

    override suspend fun addToPlaylist(playlistId: String, track: Track) {
        val pos = playlists.maxPosition(playlistId) + 1
        playlists.upsertTrack(
            PlaylistTrackEntity(
                playlistId = playlistId,
                trackId = track.id,
                title = track.title,
                artist = track.artists.joinToString { it.name },
                source = track.source.name,
                coverUrl = track.coverUrl,
                durationMs = track.durationMs,
                streamUrl = track.streamUrl,
                sourceUri = track.sourceUri,
                position = pos,
            ),
        )
        playlists.upsert(
            PlaylistEntity(
                id = playlistId,
                title = track.title, // will be overwritten if we only have track; keep id stable
                description = null,
                coverUrl = track.coverUrl,
                remoteYtmPlaylistId = null,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun removeFromPlaylist(playlistId: String, trackId: String) {
        playlists.removeTrack(playlistId, trackId)
    }

    override suspend fun syncWithYtm(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Link YTM session in a later phase"))

    override fun stats(): Flow<List<TrackStats>> =
        events.stats().map { rows ->
            rows.map {
                TrackStats(
                    trackId = it.trackId,
                    title = it.title,
                    artist = it.artist,
                    playCount = it.playCount,
                    totalListenedMs = it.totalListenedMs,
                    lastPlayedAtMs = it.lastPlayedAtMs,
                )
            }
        }

    override suspend fun recordPlay(event: PlayEvent) {
        events.insert(
            PlayEventEntity(
                trackId = event.trackId,
                title = event.title,
                artist = event.artist,
                source = event.source.name,
                playedAtMs = event.playedAtMs,
                durationMs = event.durationMs,
                listenedMs = event.listenedMs,
                completed = event.completed,
            ),
        )
    }
}
