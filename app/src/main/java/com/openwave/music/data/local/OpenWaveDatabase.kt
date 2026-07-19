package com.openwave.music.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "scrobbles")
data class ScrobbleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val source: String,
    val startedAtMs: Long,
    val listenedMs: Long,
)

@Entity(tableName = "play_events")
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val title: String,
    val artist: String,
    val source: String,
    val playedAtMs: Long,
    val durationMs: Long,
    val listenedMs: Long,
    val completed: Boolean,
    val coverUrl: String? = null,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val coverUrl: String?,
    val remoteYtmPlaylistId: String?,
    val updatedAtMs: Long,
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "trackId"])
data class PlaylistTrackEntity(
    val playlistId: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val source: String,
    val coverUrl: String?,
    val durationMs: Long,
    val streamUrl: String?,
    val sourceUri: String?,
    val position: Int,
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val trackId: String,
    val title: String,
    val artist: String,
    val source: String,
    val coverUrl: String?,
    val durationMs: Long,
    val localPath: String,
    val bytes: Long,
    val quality: String,
    val state: String,
    val downloadedAtMs: Long,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val source: String,
    val coverUrl: String?,
    val durationMs: Long,
    val streamUrl: String?,
    val sourceUri: String?,
    val addedAtMs: Long,
)

@Dao
interface ScrobbleDao {
    @Query("SELECT * FROM scrobbles ORDER BY startedAtMs DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ScrobbleEntity>>

    @Insert
    suspend fun insert(entity: ScrobbleEntity): Long
}

@Dao
interface PlayEventDao {
    @Query("SELECT * FROM play_events ORDER BY playedAtMs DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<PlayEventEntity>>

    @Insert
    suspend fun insert(entity: PlayEventEntity): Long

    @Query(
        """
        SELECT trackId, title, artist, COUNT(*) as playCount,
               SUM(listenedMs) as totalListenedMs, MAX(playedAtMs) as lastPlayedAtMs,
               MAX(coverUrl) as coverUrl, MAX(source) as source
        FROM play_events
        GROUP BY trackId
        ORDER BY playCount DESC
        LIMIT :limit
        """,
    )
    fun stats(limit: Int = 100): Flow<List<TrackStatsRow>>

    /** Distinct tracks by last listen time (most recent first). */
    @Query(
        """
        SELECT trackId, title, artist, source,
               MAX(playedAtMs) as lastPlayedAtMs, MAX(coverUrl) as coverUrl
        FROM play_events
        GROUP BY trackId
        ORDER BY lastPlayedAtMs DESC
        LIMIT :limit
        """,
    )
    fun recentTracks(limit: Int = 50): Flow<List<RecentTrackRow>>

}

data class TrackStatsRow(
    val trackId: String,
    val title: String,
    val artist: String,
    val playCount: Int,
    val totalListenedMs: Long,
    val lastPlayedAtMs: Long,
    val coverUrl: String? = null,
    val source: String? = null,
)

data class RecentTrackRow(
    val trackId: String,
    val title: String,
    val artist: String,
    val source: String,
    val lastPlayedAtMs: Long,
    val coverUrl: String? = null,
)


@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updatedAtMs DESC")
    fun all(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun get(id: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearTracks(playlistId: String)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position")
    fun tracks(playlistId: String): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    fun trackCount(playlistId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(entity: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrack(playlistId: String, trackId: String)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: String): Int
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY downloadedAtMs DESC")
    fun all(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE trackId = :trackId LIMIT 1")
    suspend fun get(trackId: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE trackId = :trackId")
    suspend fun delete(trackId: String)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAtMs DESC")
    fun all(): Flow<List<FavoriteEntity>>

    @Query("SELECT trackId FROM favorites")
    fun ids(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE trackId = :trackId)")
    suspend fun isFavorite(trackId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE trackId = :trackId")
    suspend fun delete(trackId: String)
}

@Database(
    entities = [
        ScrobbleEntity::class,
        PlayEventEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        DownloadEntity::class,
        FavoriteEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class OpenWaveDatabase : RoomDatabase() {
    abstract fun scrobbleDao(): ScrobbleDao
    abstract fun playEventDao(): PlayEventDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadDao(): DownloadDao
    abstract fun favoriteDao(): FavoriteDao
}
