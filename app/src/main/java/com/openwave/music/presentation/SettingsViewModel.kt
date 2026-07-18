package com.openwave.music.presentation

import androidx.lifecycle.ViewModel
import com.openwave.music.core.player.RadioQueueManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val radio: RadioQueueManager,
) : ViewModel() {

    val autoContinue: StateFlow<Boolean> = radio.autoContinue
    val crossplay: StateFlow<Boolean> = radio.crossplay

    fun setAutoContinue(enabled: Boolean) = radio.setAutoContinue(enabled)

    fun setCrossplay(enabled: Boolean) = radio.setCrossplay(enabled)
}
