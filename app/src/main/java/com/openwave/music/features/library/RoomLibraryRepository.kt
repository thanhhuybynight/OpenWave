package com.openwave.music.features.library

import com.openwave.music.core.domain.Album
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.LocalPlaylist
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.PlayEvent
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.TrackStats
import com.openwave.music.data.local.FavoriteDao
import com.openwave.music.data.local.FavoriteEntity
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
    private val favoriteDao: FavoriteDao,
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

    override fun favorites(): Flow<List<Track>> =
        favoriteDao.all().map { rows -> rows.map { it.toTrack() } }

    override fun favoriteIds(): Flow<Set<String>> =
        favoriteDao.ids().map { it.toSet() }

    override suspend fun isFavorite(trackId: String): Boolean =
        favoriteDao.isFavorite(trackId)

    override suspend fun addFavorite(track: Track) {
        favoriteDao.upsert(track.toFavoriteEntity())
    }

    override suspend fun removeFavorite(trackId: String) {
        favoriteDao.delete(trackId)
    }

    override suspend fun toggleFavorite(track: Track): Boolean {
        return if (favoriteDao.isFavorite(track.id)) {
            favoriteDao.delete(track.id)
            false
        } else {
            favoriteDao.upsert(track.toFavoriteEntity())
            true
        }
    }

    private fun FavoriteEntity.toTrack(): Track {
        val source = runCatching { MusicSource.valueOf(source) }
            .getOrDefault(MusicSource.UNKNOWN)
        val artists = listOf(
            Artist(
                id = "fav-artist-$trackId",
                name = artist,
                source = source,
            ),
        )
        val album = album?.takeIf { it.isNotBlank() }?.let { title ->
            Album(
                id = "fav-album-$trackId",
                title = title,
                artists = artists,
                source = source,
                coverUrl = coverUrl,
            )
        }
        return Track(
            id = trackId,
            title = title,
            artists = artists,
            album = album,
            durationMs = durationMs,
            source = source,
            coverUrl = coverUrl,
            streamUrl = streamUrl,
            sourceUri = sourceUri,
        )
    }

    private fun Track.toFavoriteEntity(): FavoriteEntity =
        FavoriteEntity(
            trackId = id,
            title = title,
            artist = artists.joinToString { it.name },
            album = album?.title,
            source = source.name,
            coverUrl = coverUrl,
            durationMs = durationMs,
            streamUrl = streamUrl,
            sourceUri = sourceUri,
            addedAtMs = System.currentTimeMillis(),
        )
}
