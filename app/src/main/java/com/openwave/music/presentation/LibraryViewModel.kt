package com.openwave.music.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwave.music.core.domain.LocalPlaylist
import com.openwave.music.core.domain.OfflineTrack
import com.openwave.music.core.domain.ScrobbleEntry
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.TrackStats
import com.openwave.music.features.LibraryRepository
import com.openwave.music.features.OfflineRepository
import com.openwave.music.features.ScrobbleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab { Playlists, Downloads, Stats, History }

/** Special open id for the built-in Favorites detail screen. */
const val FavoritesPlaylistId = "__favorites__"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val offline: OfflineRepository,
    private val scrobble: ScrobbleRepository,
) : ViewModel() {

    private val _tab = MutableStateFlow(LibraryTab.Playlists)
    val tab: StateFlow<LibraryTab> = _tab.asStateFlow()

    val playlists: StateFlow<List<LocalPlaylist>> = library.playlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favorites: StateFlow<List<Track>> = library.favorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteIds: StateFlow<Set<String>> = library.favoriteIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val downloads: StateFlow<List<OfflineTrack>> = offline.downloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<List<TrackStats>> = library.stats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val scrobbles: StateFlow<List<ScrobbleEntry>> = scrobble.recent(80)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedPlaylistId = MutableStateFlow<String?>(null)
    val selectedPlaylistId: StateFlow<String?> = _selectedPlaylistId.asStateFlow()

    val playlistTracks: StateFlow<List<Track>> = _selectedPlaylistId
        .flatMapLatest { id ->
            when {
                id == null -> flowOf(emptyList())
                id == FavoritesPlaylistId -> library.favorites()
                else -> library.playlistTracks(id)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun selectTab(tab: LibraryTab) {
        _tab.value = tab
        if (tab != LibraryTab.Playlists) _selectedPlaylistId.value = null
    }

    fun openPlaylist(id: String) {
        _selectedPlaylistId.value = id
    }

    fun closePlaylist() {
        _selectedPlaylistId.value = null
    }

    fun createPlaylist(title: String) {
        viewModelScope.launch {
            val p = library.createPlaylist(title)
            _message.value = "Created \"${p.title}\""
            openPlaylist(p.id)
        }
    }

    fun createPlaylistAndAdd(title: String, track: Track) {
        viewModelScope.launch {
            val p = library.createPlaylist(title)
            library.addToPlaylist(p.id, track)
            _message.value = "Added to \"${p.title}\""
        }
    }

    fun renamePlaylist(id: String, title: String) {
        viewModelScope.launch {
            library.renamePlaylist(id, title)
            _message.value = "Renamed"
        }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            library.deletePlaylist(id)
            if (_selectedPlaylistId.value == id) _selectedPlaylistId.value = null
            _message.value = "Playlist deleted"
        }
    }

    fun removeFromPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            if (playlistId == FavoritesPlaylistId) {
                library.removeFavorite(trackId)
            } else {
                library.removeFromPlaylist(playlistId, trackId)
            }
        }
    }

    fun addToPlaylist(playlistId: String, track: Track) {
        viewModelScope.launch {
            library.addToPlaylist(playlistId, track)
            _message.value = "Added to playlist"
        }
    }

    fun openFavorites() {
        _selectedPlaylistId.value = FavoritesPlaylistId
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            val liked = library.toggleFavorite(track)
            _message.value = if (liked) "Đã thêm vào Yêu thích" else "Đã bỏ khỏi Yêu thích"
        }
    }

    fun removeFavorite(trackId: String) {
        viewModelScope.launch {
            library.removeFavorite(trackId)
        }
    }

    fun removeDownload(trackId: String) {
        viewModelScope.launch {
            offline.remove(trackId)
            _message.value = "Download removed"
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun playlistTitle(id: String?): String = when (id) {
        FavoritesPlaylistId -> "Yêu thích"
        else -> playlists.value.firstOrNull { it.id == id }?.title ?: "Playlist"
    }
}
