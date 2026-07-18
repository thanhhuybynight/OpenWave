package com.openwave.music.features.audiofx

import com.openwave.music.core.domain.CrossfadeSettings
import com.openwave.music.features.CrossfadeController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crossfade settings + hook for player.
 * Full DJ blend needs dual ExoPlayer or Media3 audio processors (Phase 3).
 * MVP: when [onNearEnd] fires within duration, player should prepare next item.
 */
@Singleton
class CrossfadeControllerImpl @Inject constructor() : CrossfadeController {

    private val _settings = MutableStateFlow(CrossfadeSettings())
    override val settings: StateFlow<CrossfadeSettings> = _settings.asStateFlow()

    override fun update(settings: CrossfadeSettings) {
        _settings.value = settings.copy(
            durationMs = settings.durationMs.coerceIn(1_000, 20_000),
        )
    }

    override fun onNearEnd(remainingMs: Long) {
        val s = _settings.value
        if (!s.enabled) return
        if (remainingMs in 1..s.durationMs) {
            // Player layer observes this via separate callback / shared flow later
        }
    }
}
