package com.openwave.music.features.audiofx

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.openwave.music.core.domain.AudioFxState
import com.openwave.music.core.domain.EqPreset
import com.openwave.music.core.domain.SoundType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.audioFxDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "audio_fx_settings",
)

@Singleton
class AudioFxSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val state: Flow<AudioFxState> = context.audioFxDataStore.data.map { prefs ->
        val preset = runCatching {
            EqPreset.valueOf(prefs[KEY_PRESET] ?: EqPreset.FLAT.name)
        }.getOrDefault(EqPreset.FLAT)
        val soundType = runCatching {
            SoundType.valueOf(prefs[KEY_SOUND_TYPE] ?: SoundType.STEREO.name)
        }.getOrDefault(SoundType.STEREO)
        val gains = (0 until AudioFxState.BAND_COUNT).map { i ->
            prefs[bandKey(i)] ?: AudioFxState.gainsFor(preset).getOrElse(i) { 0f }
        }
        AudioFxState(
            enabled = prefs[KEY_ENABLED] ?: true,
            preset = preset,
            bandGainsDb = gains,
            soundType = soundType,
            normalizeVolume = prefs[KEY_NORMALIZE] ?: false,
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.audioFxDataStore.edit { it[KEY_ENABLED] = enabled }
    }

    suspend fun setPreset(preset: EqPreset) {
        context.audioFxDataStore.edit { prefs ->
            prefs[KEY_PRESET] = preset.name
            if (preset != EqPreset.CUSTOM) {
                AudioFxState.gainsFor(preset).forEachIndexed { i, g ->
                    prefs[bandKey(i)] = g
                }
            }
        }
    }

    suspend fun setBandGain(index: Int, gainDb: Float) {
        if (index !in 0 until AudioFxState.BAND_COUNT) return
        val clamped = gainDb.coerceIn(MIN_DB, MAX_DB)
        context.audioFxDataStore.edit { prefs ->
            prefs[bandKey(index)] = clamped
            prefs[KEY_PRESET] = EqPreset.CUSTOM.name
        }
    }

    suspend fun setBandGains(gains: List<Float>) {
        context.audioFxDataStore.edit { prefs ->
            gains.take(AudioFxState.BAND_COUNT).forEachIndexed { i, g ->
                prefs[bandKey(i)] = g.coerceIn(MIN_DB, MAX_DB)
            }
            prefs[KEY_PRESET] = EqPreset.CUSTOM.name
        }
    }

    suspend fun setSoundType(type: SoundType) {
        context.audioFxDataStore.edit { it[KEY_SOUND_TYPE] = type.name }
    }

    suspend fun setNormalizeVolume(enabled: Boolean) {
        context.audioFxDataStore.edit { it[KEY_NORMALIZE] = enabled }
    }

    companion object {
        const val MIN_DB = -15f
        const val MAX_DB = 15f

        private val KEY_ENABLED = booleanPreferencesKey("eq_enabled")
        private val KEY_PRESET = stringPreferencesKey("eq_preset")
        private val KEY_SOUND_TYPE = stringPreferencesKey("sound_type")
        private val KEY_NORMALIZE = booleanPreferencesKey("normalize_volume")

        private fun bandKey(i: Int) = floatPreferencesKey("eq_band_$i")
    }
}
