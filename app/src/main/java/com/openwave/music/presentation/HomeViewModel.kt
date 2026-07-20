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
import kotlinx.coroutines.delay
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

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _loadMoreGeneration = MutableStateFlow(0)
    val loadMoreGeneration: StateFlow<Int> = _loadMoreGeneration.asStateFlow()
    private var retryAfterMs = 0L

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            browse.invalidate()
            retryAfterMs = 0L
            _loadMoreGeneration.value++
            runCatching {
                when (browse) {
                    is YtmBrowseRepository -> browse.homeFeed()
                    else -> {
                        val shelves = browse.homeShelves()
                        HomeFeed(
                            recommendations = shelves.firstOrNull {
                                it.kind == com.openwave.music.core.domain.BrowseShelfKind.RECOMMENDATIONS
                            } ?: BrowseShelf(
                                "de_xuat",
                                "Đề xuất",
                                com.openwave.music.core.domain.BrowseShelfKind.RECOMMENDATIONS,
                            ),
                            topSongs = shelves.firstOrNull {
                                it.kind == com.openwave.music.core.domain.BrowseShelfKind.TOP_SONGS
                            } ?: BrowseShelf(
                                "songs",
                                "Bài hát hàng đầu",
                                com.openwave.music.core.domain.BrowseShelfKind.TOP_SONGS,
                            ),
                            topArtists = shelves.firstOrNull {
                                it.kind == com.openwave.music.core.domain.BrowseShelfKind.TOP_ARTISTS
                            } ?: BrowseShelf(
                                "artists",
                                "Nghệ sĩ hàng đầu",
                                com.openwave.music.core.domain.BrowseShelfKind.TOP_ARTISTS,
                            ),
                            listenAgain = shelves.firstOrNull {
                                it.kind == com.openwave.music.core.domain.BrowseShelfKind.LISTEN_AGAIN
                            } ?: BrowseShelf(
                                "nghe_lai",
                                "Nghe lại",
                                com.openwave.music.core.domain.BrowseShelfKind.LISTEN_AGAIN,
                            ),
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

    fun loadMore() {
        if (_loadingMore.value || browse !is YtmBrowseRepository || System.currentTimeMillis() < retryAfterMs) return
        _loadingMore.value = true
        viewModelScope.launch {
            try {
                val tracks = browse.loadMoreRecommendations()
                val current = _feed.value ?: return@launch
                val existing = current.recommendations.items
                val more = tracks.map { track ->
                    com.openwave.music.core.domain.BrowseItem.TrackItem(
                        track.id, track.title, track.artists.joinToString { it.name }, track.coverUrl, track,
                    )
                }
                if (more.isEmpty()) {
                    retryAfterMs = System.currentTimeMillis() + EMPTY_RETRY_COOLDOWN_MS
                    delay(EMPTY_RETRY_COOLDOWN_MS)
                    _loadMoreGeneration.value++
                    return@launch
                }
                retryAfterMs = 0L
                val updated = current.copy(
                    recommendations = current.recommendations.copy(items = existing + more),
                )
                _feed.value = updated
                _shelves.value = updated.shelves()
            } catch (t: Throwable) {
                retryAfterMs = System.currentTimeMillis() + EMPTY_RETRY_COOLDOWN_MS
                _error.value = t.message?.take(120) ?: "Không tải thêm được đề xuất"
            } finally {
                _loadingMore.value = false
            }
        }
    }

    fun download(track: Track) {
        viewModelScope.launch {
            offline.enqueue(track, StreamQuality.HIGH)
        }
    }

    /** Search & play top result for an artist chart entry. */
    fun artistSearchQuery(artist: Artist): String = artist.name

    private companion object {
        const val EMPTY_RETRY_COOLDOWN_MS = 5_000L
    }
}
