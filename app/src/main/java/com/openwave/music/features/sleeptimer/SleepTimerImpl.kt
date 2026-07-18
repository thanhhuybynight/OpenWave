package com.openwave.music.features.sleeptimer

import com.openwave.music.core.domain.SleepTimerState
import com.openwave.music.core.player.PlayerController
import com.openwave.music.features.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sleep timer: pauses playback after [durationMs].
 * Works with Media3 while screen is off.
 */
@Singleton
class SleepTimerImpl @Inject constructor(
    private val player: PlayerController,
) : SleepTimer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null

    private val _state = MutableStateFlow(SleepTimerState())
    override val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    override fun start(durationMs: Long) {
        cancel()
        if (durationMs <= 0L) return
        val ends = System.currentTimeMillis() + durationMs
        _state.value = SleepTimerState(active = true, endsAtEpochMs = ends, remainingMs = durationMs)
        tickJob = scope.launch {
            while (isActive) {
                val left = (ends - System.currentTimeMillis()).coerceAtLeast(0L)
                _state.update { it.copy(remainingMs = left) }
                if (left <= 0L) {
                    player.pause()
                    _state.value = SleepTimerState()
                    break
                }
                delay(1_000L)
            }
        }
    }

    override fun cancel() {
        tickJob?.cancel()
        tickJob = null
        _state.value = SleepTimerState()
    }
}
