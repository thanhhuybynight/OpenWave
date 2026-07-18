package com.openwave.music.presentation

import androidx.lifecycle.ViewModel
import com.openwave.music.core.domain.CrossfadeSettings
import com.openwave.music.core.player.RadioQueueManager
import com.openwave.music.features.CrossfadeController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val radio: RadioQueueManager,
    private val crossfade: CrossfadeController,
) : ViewModel() {

    val autoContinue: StateFlow<Boolean> = radio.autoContinue
    val crossfadeSettings: StateFlow<CrossfadeSettings> = crossfade.settings

    fun setAutoContinue(enabled: Boolean) = radio.setAutoContinue(enabled)

    fun setCrossfade(enabled: Boolean) {
        val cur = crossfade.settings.value
        crossfade.update(cur.copy(enabled = enabled))
    }
}
