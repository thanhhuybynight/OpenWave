package com.openwave.music.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwave.music.core.domain.BrowseShelf
import com.openwave.music.core.domain.CrossfadeSettings
import com.openwave.music.core.domain.SleepTimerState
import com.openwave.music.core.domain.StreamQuality
import com.openwave.music.core.domain.Track
import com.openwave.music.features.BrowseRepository
import com.openwave.music.features.CrossfadeController
import com.openwave.music.features.OfflineRepository
import com.openwave.music.features.SleepTimer
import com.openwave.music.features.StreamQualitySelector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val browse: BrowseRepository,
    private val sleepTimer: SleepTimer,
    private val crossfade: CrossfadeController,
    private val offline: OfflineRepository,
    private val qualitySelector: StreamQualitySelector,
) : ViewModel() {

    private val _shelves = MutableStateFlow<List<BrowseShelf>>(emptyList())
    val shelves: StateFlow<List<BrowseShelf>> = _shelves.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val sleepState: StateFlow<SleepTimerState> = sleepTimer.state
    val crossfadeSettings: StateFlow<CrossfadeSettings> = crossfade.settings

    val qualityLabel: StateFlow<String> = MutableStateFlow(
        qualitySelector.preference.preferred.name,
    ).asStateFlow()

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

    fun startSleepTimer(minutes: Int) {
        sleepTimer.start(minutes * 60_000L)
    }

    fun cancelSleepTimer() = sleepTimer.cancel()

    fun setCrossfade(enabled: Boolean, durationSec: Int = 8) {
        crossfade.update(
            CrossfadeSettings(enabled = enabled, durationMs = durationSec * 1000),
        )
    }

    fun setQuality(q: StreamQuality) {
        qualitySelector.preference = qualitySelector.preference.copy(preferred = q)
    }

    fun download(track: Track) {
        viewModelScope.launch {
            offline.enqueue(track, StreamQuality.HIGH)
        }
    }
}
