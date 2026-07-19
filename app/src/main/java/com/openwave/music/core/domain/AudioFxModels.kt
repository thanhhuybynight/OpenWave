package com.openwave.music.core.domain

/**
 * In-app audio chain settings (Equalizer + channel mode + loudness).
 * Applies only to OpenWave playback session — not system-wide.
 */

enum class SoundType {
    STEREO,
    MONO,
}

enum class EqPreset {
    FLAT,
    POP,
    JAZZ,
    EDM,
    BASSBOOST,
    CUSTOM,
}

/**
 * Five logical bands shown in Settings UI (mapped onto device EQ centers).
 * Labels: Bass · Low-mid · Mid · High-mid · Treble
 * Values are dB in roughly [-15, +15].
 */
data class AudioFxState(
    val enabled: Boolean = true,
    val preset: EqPreset = EqPreset.FLAT,
    /** Length always [BAND_COUNT]. */
    val bandGainsDb: List<Float> = FlatGains,
    val soundType: SoundType = SoundType.STEREO,
    /** Soft loudness leveling / anti-blast boost for quiet masters. */
    val normalizeVolume: Boolean = false,
) {
    companion object {
        const val BAND_COUNT = 5
        val BandLabels = listOf("Bass", "Low-mid", "Mid", "High-mid", "Treble")
        /** Approximate center frequencies (Hz) for UI mapping. */
        val BandCentersHz = listOf(60, 230, 910, 3600, 14_000)

        val FlatGains = List(BAND_COUNT) { 0f }

        fun gainsFor(preset: EqPreset): List<Float> = when (preset) {
            EqPreset.FLAT -> FlatGains
            // Mild presence, slight low cut
            EqPreset.POP -> listOf(-1.5f, 2f, 3.5f, 2.5f, -1f)
            // Warm lows, open highs
            EqPreset.JAZZ -> listOf(3f, 1.5f, -1f, 1.5f, 3f)
            // Punchy lows + bright top
            EqPreset.EDM -> listOf(5f, 2.5f, -1.5f, 2f, 4f)
            // Strong low end
            EqPreset.BASSBOOST -> listOf(8f, 4.5f, 0.5f, -1f, 1f)
            EqPreset.CUSTOM -> FlatGains
        }

        fun default() = AudioFxState()
    }
}
