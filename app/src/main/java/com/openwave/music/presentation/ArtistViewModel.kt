package com.openwave.music.presentation

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwave.music.core.domain.Album
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.ArtistPage
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.Track
import com.openwave.music.data.source.newpipe.NewPipeBootstrap
import com.openwave.music.data.source.youtube.YouTubeMusicSourceClient
import com.openwave.music.features.artist.ArtistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject

@HiltViewModel
class ArtistViewModel @Inject constructor(
    private val artists: ArtistRepository,
    private val newPipe: NewPipeBootstrap,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val name: String = decode(savedStateHandle.get<String>("name")).orEmpty()
    private val artistId: String? = decode(savedStateHandle.get<String>("id"))?.takeIf { it.isNotBlank() }
    private val avatar: String? = decode(savedStateHandle.get<String>("avatar"))?.takeIf { it.isNotBlank() }
    private val channelId: String? =
        decode(savedStateHandle.get<String>("channel"))?.takeIf { it.isNotBlank() }

    private val _page = MutableStateFlow<ArtistPage?>(null)
    val page: StateFlow<ArtistPage?> = _page.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _albumLoading = MutableStateFlow(false)
    val albumLoading: StateFlow<Boolean> = _albumLoading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (name.isBlank() && channelId.isNullOrBlank()) {
            _error.value = "Thiếu thông tin nghệ sĩ"
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching {
                artists.load(
                    name = name.ifBlank { "Artist" },
                    channelId = channelId,
                    avatarUrl = avatar,
                    artistId = artistId,
                )
            }.onSuccess {
                _page.value = it
            }.onFailure { t ->
                _error.value = t.message?.take(140) ?: "Không tải được trang nghệ sĩ"
            }
            _loading.value = false
        }
    }

    /**
     * Resolve album playlist tracks for queue playback.
     */
    suspend fun loadAlbumTracks(album: Album): List<Track> = withContext(Dispatchers.IO) {
        val url = album.sourceUri ?: return@withContext emptyList()
        _albumLoading.value = true
        try {
            newPipe.ensureInit()
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
            val artist = _page.value?.artist
            info.relatedItems.mapNotNull { item ->
                val id = YouTubeMusicSourceClient.extractVideoId(item.url ?: return@mapNotNull null)
                    ?: return@mapNotNull null
                Track(
                    id = id,
                    title = item.name.orEmpty().ifBlank { return@mapNotNull null },
                    artists = listOfNotNull(artist).ifEmpty {
                        listOf(
                            Artist(
                                id = "yt-unknown",
                                name = item.uploaderName.orEmpty().ifBlank { "YouTube" },
                                source = MusicSource.YOUTUBE_MUSIC,
                            ),
                        )
                    },
                    durationMs = if (item.duration > 0) item.duration * 1000 else 0L,
                    source = MusicSource.YOUTUBE_MUSIC,
                    coverUrl = item.thumbnails?.firstOrNull()?.url ?: album.coverUrl,
                    sourceUri = item.url,
                    album = album,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "album tracks: ${t.message}")
            emptyList()
        } finally {
            _albumLoading.value = false
        }
    }

    companion object {
        private const val TAG = "ArtistVM"

        private fun decode(raw: String?): String? {
            if (raw.isNullOrBlank()) return raw
            return runCatching {
                URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
            }.getOrDefault(raw)
        }
    }
}
