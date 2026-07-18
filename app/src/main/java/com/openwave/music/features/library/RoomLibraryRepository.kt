package com.openwave.music.features.library

import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.LocalPlaylist
import com.openwave.music.core.domain.MusicSource
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
            list.map { entity ->
                LocalPlaylist(
                    id = entity.id,
                    title = entity.title,
                    description = entity.description,
                    coverUrl = entity.coverUrl,
                    remoteYtmPlaylistId = entity.remoteYtmPlaylistId,
                    updatedAtMs = entity.updatedAtMs,
                    trackIds = emptyList(),
                )
            }
        }

    override fun playlistTracks(playlistId: String): Flow<List<Track>> =
        playlists.tracks(playlistId).map { rows ->
            rows.map { row ->
                val source = runCatching { MusicSource.valueOf(row.source) }
                    .getOrDefault(MusicSource.UNKNOWN)
                Track(
                    id = row.trackId,
                    title = row.title,
                    artists = listOf(
                        Artist(
                            id = "pl-artist-${row.trackId}",
                            name = row.artist,
                            source = source,
                        ),
                    ),
                    durationMs = row.durationMs,
                    source = source,
                    coverUrl = row.coverUrl,
                    streamUrl = row.streamUrl,
                    sourceUri = row.sourceUri,
                )
            }
        }

    override suspend fun createPlaylist(title: String): LocalPlaylist {
        val p = LocalPlaylist(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifBlank { "Playlist" },
        )
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

    override suspend fun renamePlaylist(playlistId: String, title: String) {
        val existing = playlists.get(playlistId) ?: return
        playlists.upsert(
            existing.copy(
                title = title.trim().ifBlank { existing.title },
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun deletePlaylist(playlistId: String) {
        playlists.clearTracks(playlistId)
        playlists.delete(playlistId)
    }

    override suspend fun addToPlaylist(playlistId: String, track: Track) {
        val existing = playlists.get(playlistId) ?: return
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
            existing.copy(
                coverUrl = existing.coverUrl ?: track.coverUrl,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun removeFromPlaylist(playlistId: String, trackId: String) {
        playlists.removeTrack(playlistId, trackId)
        playlists.get(playlistId)?.let {
            playlists.upsert(it.copy(updatedAtMs = System.currentTimeMillis()))
        }
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
                    coverUrl = it.coverUrl,
                    source = runCatching {
                        com.openwave.music.core.domain.MusicSource.valueOf(it.source.orEmpty())
                    }.getOrDefault(com.openwave.music.core.domain.MusicSource.UNKNOWN),
                )
            }
        }

    override fun recentPlays(limit: Int): Flow<List<com.openwave.music.core.domain.RecentPlay>> =
        events.recentTracks(limit).map { rows ->
            rows.map {
                com.openwave.music.core.domain.RecentPlay(
                    trackId = it.trackId,
                    title = it.title,
                    artist = it.artist,
                    source = runCatching {
                        com.openwave.music.core.domain.MusicSource.valueOf(it.source)
                    }.getOrDefault(com.openwave.music.core.domain.MusicSource.UNKNOWN),
                    lastPlayedAtMs = it.lastPlayedAtMs,
                    coverUrl = it.coverUrl,
                )
            }
        }

    override fun recentArtists(limit: Int): Flow<List<com.openwave.music.core.domain.RecentArtist>> =
        // Pull recent track rows then expand multi-artist credits into separate chips.
        events.recentTracks((limit * 4).coerceAtLeast(40)).map { rows ->
            data class Agg(
                var lastPlayedAtMs: Long,
                var playCount: Int,
                var coverUrl: String?,
            )
            data class Agg2(
                var name: String,
                var channelId: String?,
                var lastPlayedAtMs: Long,
                var playCount: Int,
            )
            val map = linkedMapOf<String, Agg2>()
            for (row in rows) {
                val credits = com.openwave.music.core.domain.ArtistNameSplitter
                    .splitDetailed(row.artist)
                    .ifEmpty {
                        listOfNotNull(
                            row.artist.takeIf { it.isNotBlank() }?.let {
                                com.openwave.music.core.domain.ArtistNameSplitter.Credit(it)
                            },
                        )
                    }
                for (c in credits) {
                    val key = c.channelId?.lowercase() ?: c.name.lowercase()
                    val cur = map[key]
                    if (cur == null) {
                        map[key] = Agg2(
                            name = c.name,
                            channelId = c.channelId,
                            lastPlayedAtMs = row.lastPlayedAtMs,
                            playCount = 1,
                        )
                    } else {
                        cur.playCount += 1
                        if (row.lastPlayedAtMs > cur.lastPlayedAtMs) {
                            cur.lastPlayedAtMs = row.lastPlayedAtMs
                            cur.name = c.name
                            if (c.channelId != null) cur.channelId = c.channelId
                        }
                    }
                }
            }
            map.values
                .map {
                    com.openwave.music.core.domain.RecentArtist(
                        name = it.name,
                        lastPlayedAtMs = it.lastPlayedAtMs,
                        playCount = it.playCount,
                        coverUrl = null,
                        channelId = it.channelId,
                    )
                }
                .sortedByDescending { it.lastPlayedAtMs }
                .take(limit)
        }

    override suspend fun recordPlay(event: PlayEvent) {
        events.insert(
            PlayEventEntity(
                trackId = event.trackId,
                title = event.title,
                // Prefer structured multi-artist encoding when writer used joinToString
                artist = event.artist,
                source = event.source.name,
                playedAtMs = event.playedAtMs,
                durationMs = event.durationMs,
                listenedMs = event.listenedMs,
                completed = event.completed,
                coverUrl = event.coverUrl,
            ),
        )
    }
}
