package com.openwave.music.features.offline

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.DownloadState
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.OfflineTrack
import com.openwave.music.core.domain.StreamQuality
import com.openwave.music.core.domain.Track
import com.openwave.music.data.local.DownloadDao
import com.openwave.music.data.local.DownloadEntity
import com.openwave.music.features.OfflineRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiskOfflineRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
) : OfflineRepository {

    override fun downloads(): Flow<List<OfflineTrack>> =
        dao.all().map { list -> list.map { it.toDomain() } }

    override suspend fun enqueue(track: Track, quality: StreamQuality) {
        val existing = dao.get(track.id)
        if (existing?.state == DownloadState.COMPLETED.name && existing.localPath.isNotBlank()) {
            return
        }
        dao.upsert(
            DownloadEntity(
                trackId = track.id,
                title = track.title,
                artist = track.artists.joinToString { it.name },
                source = track.source.name,
                coverUrl = track.coverUrl,
                durationMs = track.durationMs,
                localPath = "",
                bytes = 0L,
                quality = quality.name,
                state = DownloadState.QUEUED.name,
                downloadedAtMs = System.currentTimeMillis(),
            ),
        )
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_TRACK_ID to track.id,
                    DownloadWorker.KEY_TITLE to track.title,
                    DownloadWorker.KEY_ARTIST to track.artists.joinToString { it.name },
                    DownloadWorker.KEY_SOURCE to track.source.name,
                    DownloadWorker.KEY_COVER to (track.coverUrl ?: ""),
                    DownloadWorker.KEY_DURATION to track.durationMs,
                    DownloadWorker.KEY_QUALITY to quality.name,
                    DownloadWorker.KEY_SOURCE_URI to (track.sourceUri ?: ""),
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "download_${track.id}",
            ExistingWorkPolicy.KEEP,
            req,
        )
    }

    override suspend fun remove(trackId: String) {
        dao.get(trackId)?.localPath?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { java.io.File(path).delete() }
        }
        dao.delete(trackId)
        WorkManager.getInstance(context).cancelUniqueWork("download_$trackId")
    }

    override suspend fun localStreamPath(trackId: String): String? =
        dao.get(trackId)
            ?.takeIf { it.state == DownloadState.COMPLETED.name }
            ?.localPath
            ?.takeIf { it.isNotBlank() && java.io.File(it).exists() }

    private fun DownloadEntity.toDomain(): OfflineTrack {
        val source = runCatching { MusicSource.valueOf(source) }.getOrDefault(MusicSource.UNKNOWN)
        return OfflineTrack(
            trackId = trackId,
            track = Track(
                id = trackId,
                title = title,
                artists = listOf(Artist(id = "offline", name = artist, source = source)),
                durationMs = durationMs,
                source = source,
                coverUrl = coverUrl,
                streamUrl = localPath.takeIf { it.isNotBlank() },
            ),
            localPath = localPath,
            bytes = bytes,
            quality = runCatching { StreamQuality.valueOf(quality) }.getOrDefault(StreamQuality.HIGH),
            state = runCatching { DownloadState.valueOf(state) }.getOrDefault(DownloadState.QUEUED),
            downloadedAtMs = downloadedAtMs,
        )
    }
}
