package com.openwave.music.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwave.music.core.domain.BrowseShelf
import com.openwave.music.core.domain.StreamQuality
import com.openwave.music.core.domain.Track
import com.openwave.music.features.BrowseRepository
import com.openwave.music.features.OfflineRepository
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

    private val _shelves = MutableStateFlow<List<BrowseShelf>>(emptyList())
    val shelves: StateFlow<List<BrowseShelf>> = _shelves.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            browse.invalidate()
            _shelves.value = runCatching { browse.homeShelves() }.getOrDefault(emptyList())
            _loading.value = false
        }
    }

    fun download(track: Track) {
        viewModelScope.launch {
            offline.enqueue(track, StreamQuality.HIGH)
        }
    }
}
