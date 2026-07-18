package com.openwave.music.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.BrowseShelf
import com.openwave.music.core.domain.HomeFeed
import com.openwave.music.core.domain.StreamQuality
import com.openwave.music.core.domain.Track
import com.openwave.music.features.BrowseRepository
import com.openwave.music.features.OfflineRepository
import com.openwave.music.features.browse.YtmBrowseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val browse: BrowseRepository,
    private val offline: OfflineRepository,
) : ViewModel() {

    private val _feed = MutableStateFlow<HomeFeed?>(null)
    val feed: StateFlow<HomeFeed?> = _feed.asStateFlow()

    private val _shelves = MutableStateFlow<List<BrowseShelf>>(emptyList())
    val shelves: StateFlow<List<BrowseShelf>> = _shelves.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            browse.invalidate()
            runCatching {
                when (browse) {
                    is YtmBrowseRepository -> browse.homeFeed()
                    else -> {
                        val shelves = browse.homeShelves()
                        HomeFeed(
                            recommendations = shelves.firstOrNull()
                                ?: BrowseShelf("de_xuat", "Đề xuất", com.openwave.music.core.domain.BrowseShelfKind.RECOMMENDATIONS),
                            topSongs = shelves.getOrNull(1)
                                ?: BrowseShelf("songs", "Bài hát hàng đầu", com.openwave.music.core.domain.BrowseShelfKind.TOP_SONGS),
                            topArtists = shelves.getOrNull(2)
                                ?: BrowseShelf("artists", "Nghệ sĩ hàng đầu", com.openwave.music.core.domain.BrowseShelfKind.TOP_ARTISTS),
                        )
                    }
                }
            }.onSuccess { feed ->
                _feed.value = feed
                _shelves.value = feed.shelves()
            }.onFailure { t ->
                _error.value = t.message?.take(120) ?: "Không tải được trang chủ"
                _feed.value = null
                _shelves.value = emptyList()
            }
            _loading.value = false
        }
    }

    fun download(track: Track) {
        viewModelScope.launch {
            offline.enqueue(track, StreamQuality.HIGH)
        }
    }

    /** Search & play top result for an artist chart entry. */
    fun artistSearchQuery(artist: Artist): String = artist.name
}
