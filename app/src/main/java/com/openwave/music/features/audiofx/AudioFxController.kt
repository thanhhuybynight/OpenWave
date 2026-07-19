package com.openwave.music.features.audiofx

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import com.openwave.music.core.domain.AudioFxState
import com.openwave.music.core.domain.SoundType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * App-only playback chain:
 * - [Equalizer] on ExoPlayer audio session
 * - [ChannelMixingAudioProcessor] for Stereo / Mono
 * - [LoudnessEnhancer] + optional [DynamicsProcessing] limiter for Normalize Volume
 */
@OptIn(UnstableApi::class)
@Singleton
class AudioFxController @Inject constructor(
    private val store: AudioFxSettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(AudioFxState.default())
    val state: StateFlow<AudioFxState> = _state.asStateFlow()

    /** Wired into DefaultAudioSink — always present; matrix switches stereo/mono. */
    val channelMixingProcessor: ChannelMixingAudioProcessor = ChannelMixingAudioProcessor().also {
        applyChannelMatrix(it, SoundType.STEREO)
    }

    val audioProcessors: Array<AudioProcessor>
        get() = arrayOf(channelMixingProcessor)

    private var sessionId: Int = 0
    private var equalizer: Equalizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var dynamics: DynamicsProcessing? = null

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            store.state.collectLatest { s ->
                _state.value = s
                applyAll(s)
            }
        }
    }

    /**
     * Called from [com.openwave.music.core.player.PlaybackService] whenever
     * ExoPlayer reports a (new) audio session id.
     */
    fun attachSession(audioSessionId: Int) {
        if (audioSessionId == 0) return
        scope.launch {
            mutex.withLock {
                if (sessionId == audioSessionId && equalizer != null) {
                    applyAllLocked(_state.value)
                    return@withLock
                }
                releaseEffectsLocked()
                sessionId = audioSessionId
                createEffectsLocked(audioSessionId)
                applyAllLocked(_state.value)
                Log.i(TAG, "attached session=$audioSessionId eq=${equalizer != null}")
            }
        }
    }

    fun release() {
        scope.launch {
            mutex.withLock { releaseEffectsLocked() }
        }
    }

    // ── Settings API (async persist) ────────────────────────────────────────

    fun setEnabled(enabled: Boolean) = scope.launch { store.setEnabled(enabled) }

    fun setPreset(preset: com.openwave.music.core.domain.EqPreset) =
        scope.launch { store.setPreset(preset) }

    fun setBandGain(index: Int, gainDb: Float) =
        scope.launch { store.setBandGain(index, gainDb) }

    fun setSoundType(type: SoundType) = scope.launch { store.setSoundType(type) }

    fun setNormalizeVolume(enabled: Boolean) =
        scope.launch { store.setNormalizeVolume(enabled) }

    // ── Internals ───────────────────────────────────────────────────────────

    private suspend fun applyAll(s: AudioFxState) {
        mutex.withLock { applyAllLocked(s) }
    }

    private fun applyAllLocked(s: AudioFxState) {
        applyChannelMatrix(channelMixingProcessor, s.soundType)
        val eq = equalizer
        if (eq != null) {
            try {
                eq.enabled = s.enabled
                if (s.enabled) {
                    applyEqualizerGains(eq, s.bandGainsDb)
                } else {
                    // Flat when disabled
                    applyEqualizerGains(eq, AudioFxState.FlatGains)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "eq apply: ${t.message}")
            }
        }
        applyNormalizeLocked(s.normalizeVolume)
    }

    private fun applyNormalizeLocked(enabled: Boolean) {
        try {
            loudness?.let { le ->
                le.enabled = enabled
                if (enabled) {
                    // Soft boost for quiet masters (millibels)
                    le.setTargetGain(NORMALIZE_TARGET_MB)
                }
            }
            dynamics?.let { dp ->
                dp.enabled = enabled
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Peak limiter to curb earrape spikes
                    val limiter = DynamicsProcessing.Limiter(
                        true,
                        true,
                        0,
                        1f, // attack ms
                        50f, // release ms
                        3f, // ratio
                        0f, // threshold dBFS — soft ceiling
                        0f, // post gain
                    )
                    dp.setLimiterAllChannelsTo(limiter)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "normalize: ${t.message}")
        }
    }

    private fun createEffectsLocked(session: Int) {
        try {
            equalizer = Equalizer(0, session).also { it.enabled = true }
        } catch (t: Throwable) {
            Log.e(TAG, "Equalizer init: ${t.message}")
            equalizer = null
        }
        try {
            loudness = LoudnessEnhancer(session).also { it.enabled = false }
        } catch (t: Throwable) {
            Log.w(TAG, "LoudnessEnhancer: ${t.message}")
            loudness = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val cfg = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    /* channelCount */ 2,
                    /* preEqInUse */ false,
                    /* preEqBandCount */ 0,
                    /* mbcInUse */ false,
                    /* mbcBandCount */ 0,
                    /* postEqInUse */ false,
                    /* postEqBandCount */ 0,
                    /* limiterInUse */ true,
                ).build()
                dynamics = DynamicsProcessing(0, session, cfg).also { it.enabled = false }
            } catch (t: Throwable) {
                Log.w(TAG, "DynamicsProcessing: ${t.message}")
                dynamics = null
            }
        }
    }

    private fun releaseEffectsLocked() {
        try {
            equalizer?.release()
        } catch (_: Throwable) {
        }
        try {
            loudness?.release()
        } catch (_: Throwable) {
        }
        try {
            dynamics?.release()
        } catch (_: Throwable) {
        }
        equalizer = null
        loudness = null
        dynamics = null
        sessionId = 0
    }

    /**
     * Map 5 UI bands onto whatever center frequencies the device EQ exposes.
     */
    private fun applyEqualizerGains(eq: Equalizer, gainsDb: List<Float>) {
        val n = eq.numberOfBands.toInt()
        if (n <= 0) return
        val levelRange = eq.bandLevelRange // short[2] min/max millibels
        val minMb = levelRange[0].toInt()
        val maxMb = levelRange[1].toInt()
        for (band in 0 until n) {
            val centerMilliHz = eq.getCenterFreq(band.toShort())
            val centerHz = centerMilliHz / 1000f
            val db = interpolateGainDb(centerHz, gainsDb)
            val mb = (db * 100f).roundToInt().coerceIn(minMb, maxMb)
            eq.setBandLevel(band.toShort(), mb.toShort())
        }
    }

    /**
     * Log-frequency interpolation across the 5 logical UI bands.
     */
    private fun interpolateGainDb(freqHz: Float, gainsDb: List<Float>): Float {
        val centers = AudioFxState.BandCentersHz
        if (gainsDb.isEmpty()) return 0f
        if (freqHz <= centers.first()) return gainsDb.first()
        if (freqHz >= centers.last()) return gainsDb.last()
        for (i in 0 until centers.lastIndex) {
            val f0 = centers[i].toFloat()
            val f1 = centers[i + 1].toFloat()
            if (freqHz in f0..f1) {
                val g0 = gainsDb.getOrElse(i) { 0f }
                val g1 = gainsDb.getOrElse(i + 1) { 0f }
                val t = logLerp(freqHz, f0, f1)
                return g0 + (g1 - g0) * t
            }
        }
        return 0f
    }

    private fun logLerp(f: Float, f0: Float, f1: Float): Float {
        if (f0 <= 0f || f1 <= 0f || abs(f1 - f0) < 1e-3f) return 0f
        val a = ln(f.toDouble())
        val b = ln(f0.toDouble())
        val c = ln(f1.toDouble())
        return ((a - b) / (c - b)).toFloat().coerceIn(0f, 1f)
    }

    private fun applyChannelMatrix(processor: ChannelMixingAudioProcessor, type: SoundType) {
        try {
            // Stereo passthrough
            val stereo = ChannelMixingMatrix(
                /* inputChannelCount = */ 2,
                /* outputChannelCount = */ 2,
                /* coefficients = */ floatArrayOf(
                    1f, 0f,
                    0f, 1f,
                ),
            )
            // Mono: average L+R into both speakers
            val mono = ChannelMixingMatrix(
                2,
                2,
                floatArrayOf(
                    0.5f, 0.5f,
                    0.5f, 0.5f,
                ),
            )
            // Also handle mono input (1 ch) → stereo out
            val monoIn = ChannelMixingMatrix(
                1,
                2,
                floatArrayOf(1f, 1f),
            )
            processor.putChannelMixingMatrix(monoIn)
            processor.putChannelMixingMatrix(if (type == SoundType.MONO) mono else stereo)
        } catch (t: Throwable) {
            Log.w(TAG, "channel matrix: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "AudioFx"
        /** ~6 dB soft target for LoudnessEnhancer when normalize is on. */
        private const val NORMALIZE_TARGET_MB = 600
    }
}
