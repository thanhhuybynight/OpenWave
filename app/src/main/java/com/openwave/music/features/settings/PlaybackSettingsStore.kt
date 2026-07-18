package com.openwave.music.features.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "playback_settings",
)

/**
 * Persisted player preferences (auto-radio, crossplay, …).
 */
@Singleton
class PlaybackSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val autoContinue: Flow<Boolean> = context.playbackDataStore.data.map { prefs ->
        prefs[KEY_AUTO_CONTINUE] ?: true
    }

    /**
     * When true, auto-queue / station may mix SoundCloud + YouTube Music.
     * When false, next tracks stay on the seed track's source only.
     */
    val crossplay: Flow<Boolean> = context.playbackDataStore.data.map { prefs ->
        prefs[KEY_CROSSPLAY] ?: true
    }

    suspend fun setAutoContinue(enabled: Boolean) {
        context.playbackDataStore.edit { it[KEY_AUTO_CONTINUE] = enabled }
    }

    suspend fun setCrossplay(enabled: Boolean) {
        context.playbackDataStore.edit { it[KEY_CROSSPLAY] = enabled }
    }

    companion object {
        private val KEY_AUTO_CONTINUE = booleanPreferencesKey("auto_continue")
        private val KEY_CROSSPLAY = booleanPreferencesKey("crossplay")
    }
}
