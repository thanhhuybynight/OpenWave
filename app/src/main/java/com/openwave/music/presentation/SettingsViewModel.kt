package com.openwave.music.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwave.music.core.domain.AudioFxState
import com.openwave.music.core.domain.EqPreset
import com.openwave.music.core.domain.SoundType
import com.openwave.music.core.player.RadioQueueManager
import com.openwave.music.features.audiofx.AudioFxController
import com.openwave.music.features.audiofx.AudioFxSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val radio: RadioQueueManager,
    private val audioFx: AudioFxController,
    audioFxStore: AudioFxSettingsStore,
) : ViewModel() {

    val autoContinue: StateFlow<Boolean> = radio.autoContinue
    val crossplay: StateFlow<Boolean> = radio.crossplay

    val audioFxState: StateFlow<AudioFxState> = audioFxStore.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AudioFxState.default())

    fun setAutoContinue(enabled: Boolean) = radio.setAutoContinue(enabled)

    fun setCrossplay(enabled: Boolean) = radio.setCrossplay(enabled)

    fun setEqEnabled(enabled: Boolean) = audioFx.setEnabled(enabled)

    fun setEqPreset(preset: EqPreset) = audioFx.setPreset(preset)

    fun setEqBand(index: Int, gainDb: Float) = audioFx.setBandGain(index, gainDb)

    fun setSoundType(type: SoundType) = audioFx.setSoundType(type)

    fun setNormalizeVolume(enabled: Boolean) = audioFx.setNormalizeVolume(enabled)
}
