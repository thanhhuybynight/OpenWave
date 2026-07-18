package com.openwave.music.features.offline

import com.openwave.music.core.domain.DownloadState
import com.openwave.music.core.domain.OfflineTrack
import com.openwave.music.core.domain.StreamQuality
import com.openwave.music.core.domain.Track
import com.openwave.music.features.OfflineRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline queue skeleton. Real downloads: OkHttp → app filesDir/tracks/{id}.m4a
 * after stream resolve (Phase 2C).
 */
@Singleton
class InMemoryOfflineRepository @Inject constructor() : OfflineRepository {

    private val _downloads = MutableStateFlow<List<OfflineTrack>>(emptyList())

    override fun downloads(): Flow<List<OfflineTrack>> = _downloads.asStateFlow()

    override suspend fun enqueue(track: Track, quality: StreamQuality) {
        if (_downloads.value.any { it.trackId == track.id }) return
        val stub = OfflineTrack(
            trackId = track.id,
            track = track,
            localPath = "", // filled when download worker completes
            bytes = 0L,
            quality = quality,
            state = DownloadState.QUEUED,
        )
        _downloads.update { it + stub }
        // Worker would set DOWNLOADING → COMPLETED and localPath
    }

    override suspend fun remove(trackId: String) {
        _downloads.update { list -> list.filterNot { it.trackId == trackId } }
    }

    override suspend fun localStreamPath(trackId: String): String? =
        _downloads.value
            .firstOrNull { it.trackId == trackId && it.state == DownloadState.COMPLETED }
            ?.localPath
            ?.takeIf { it.isNotBlank() }
}
