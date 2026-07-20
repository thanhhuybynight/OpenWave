package com.openwave.music.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwave.music.core.domain.Playlist
import com.openwave.music.core.domain.Track
import com.openwave.music.data.source.soundcloud.SoundCloudAccount
import com.openwave.music.data.source.soundcloud.SoundCloudAccountClient
import com.openwave.music.features.settings.SoundCloudSessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SoundCloudSessionViewModel @Inject constructor(
    private val session: SoundCloudSessionStore,
    private val client: SoundCloudAccountClient,
) : ViewModel() {
    val loggedIn = session.loggedIn.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val _account = MutableStateFlow<SoundCloudAccount?>(null)
    val account: StateFlow<SoundCloudAccount?> = _account.asStateFlow()
    private val _likes = MutableStateFlow<List<Track>>(emptyList())
    val likes: StateFlow<List<Track>> = _likes.asStateFlow()
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { if (session.oauthToken() != null) refresh() }

    fun finishLogin(onResult: (Boolean) -> Unit) = viewModelScope.launch {
        _loading.value = true
        _error.value = null
        val me = runCatching { client.account() }.getOrNull()
        session.setLoggedIn(me != null)
        if (me != null) loadLibrary(me)
        else _error.value = "Phiên đăng nhập SoundCloud không hợp lệ"
        _loading.value = false
        onResult(me != null)
    }

    fun refresh() = viewModelScope.launch {
        _loading.value = true
        _error.value = null
        val me = runCatching { client.account() }.getOrNull()
        if (me == null) {
            session.setLoggedIn(false)
            _error.value = "Không thể xác thực phiên SoundCloud"
        } else {
            session.setLoggedIn(true)
            loadLibrary(me)
        }
        _loading.value = false
    }

    private suspend fun loadLibrary(me: SoundCloudAccount) {
        _account.value = me
        runCatching { client.likedTracks(me.id) }
            .onSuccess { _likes.value = it }
            .onFailure { _error.value = it.message ?: "Không tải được SoundCloud Likes" }
        runCatching { client.playlists(me.id) }
            .onSuccess { _playlists.value = it }
            .onFailure { _error.value = it.message ?: "Không tải được playlist SoundCloud" }
    }

    suspend fun tracks(playlist: Playlist): Result<List<Track>> =
        runCatching { playlist.tracks.ifEmpty { client.playlistTracks(playlist.id) } }

    fun clearError() { _error.value = null }

    fun logout() = viewModelScope.launch {
        session.logout()
        _account.value = null
        _likes.value = emptyList()
        _playlists.value = emptyList()
        _error.value = null
    }
}
