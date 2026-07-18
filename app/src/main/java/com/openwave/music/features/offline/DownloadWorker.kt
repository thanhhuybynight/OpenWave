package com.openwave.music.features.offline

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.DownloadState
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.StreamQuality
import com.openwave.music.core.domain.Track
import com.openwave.music.data.local.DownloadDao
import com.openwave.music.data.local.DownloadEntity
import com.openwave.music.core.domain.FastMusicCatalog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val catalog: FastMusicCatalog,
    private val dao: DownloadDao,
    private val http: OkHttpClient,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trackId = inputData.getString(KEY_TRACK_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val artist = inputData.getString(KEY_ARTIST).orEmpty()
        val sourceName = inputData.getString(KEY_SOURCE) ?: MusicSource.UNKNOWN.name
        val cover = inputData.getString(KEY_COVER)
        val duration = inputData.getLong(KEY_DURATION, 0L)
        val quality = inputData.getString(KEY_QUALITY) ?: StreamQuality.HIGH.name
        val sourceUri = inputData.getString(KEY_SOURCE_URI)

        val source = runCatching { MusicSource.valueOf(sourceName) }.getOrDefault(MusicSource.UNKNOWN)
        val track = Track(
            id = trackId,
            title = title,
            artists = listOf(Artist("dl", artist, source)),
            durationMs = duration,
            source = source,
            coverUrl = cover?.ifBlank { null },
            sourceUri = sourceUri?.ifBlank { null },
        )

        dao.upsert(
            DownloadEntity(
                trackId = trackId,
                title = title,
                artist = artist,
                source = sourceName,
                coverUrl = cover,
                durationMs = duration,
                localPath = "",
                bytes = 0L,
                quality = quality,
                state = DownloadState.DOWNLOADING.name,
                downloadedAtMs = System.currentTimeMillis(),
            ),
        )

        return try {
            val stream = catalog.resolveStreamFast(track)
            val dir = File(applicationContext.filesDir, "tracks").apply { mkdirs() }
            val ext = when {
                stream.mimeType?.contains("webm") == true -> "webm"
                stream.mimeType?.contains("mp4") == true || stream.mimeType?.contains("m4a") == true -> "m4a"
                else -> "mp3"
            }
            val out = File(dir, "${trackId.hashCode().toUInt()}.$ext")
            val req = Request.Builder().url(stream.url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.byteStream()?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                } ?: error("empty body")
            }
            dao.upsert(
                DownloadEntity(
                    trackId = trackId,
                    title = title,
                    artist = artist,
                    source = sourceName,
                    coverUrl = cover,
                    durationMs = duration,
                    localPath = out.absolutePath,
                    bytes = out.length(),
                    quality = quality,
                    state = DownloadState.COMPLETED.name,
                    downloadedAtMs = System.currentTimeMillis(),
                ),
            )
            Result.success()
        } catch (t: Throwable) {
            dao.upsert(
                DownloadEntity(
                    trackId = trackId,
                    title = title,
                    artist = artist,
                    source = sourceName,
                    coverUrl = cover,
                    durationMs = duration,
                    localPath = "",
                    bytes = 0L,
                    quality = quality,
                    state = DownloadState.FAILED.name,
                    downloadedAtMs = System.currentTimeMillis(),
                ),
            )
            Result.retry()
        }
    }

    companion object {
        const val KEY_TRACK_ID = "trackId"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_SOURCE = "source"
        const val KEY_COVER = "cover"
        const val KEY_DURATION = "duration"
        const val KEY_QUALITY = "quality"
        const val KEY_SOURCE_URI = "sourceUri"
    }
}
