package com.openwave.music.features.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.displayDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "display_settings",
)

@Singleton
class DisplaySettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val densityScale: Flow<Float> = context.displayDataStore.data.map { prefs ->
        (prefs[KEY_DENSITY_SCALE] ?: DEFAULT_SCALE).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    suspend fun setDensityScale(scale: Float) {
        context.displayDataStore.edit {
            it[KEY_DENSITY_SCALE] = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        }
    }

    companion object {
        const val DEFAULT_SCALE = 1f
        const val MIN_SCALE = 0.7f
        const val MAX_SCALE = 1.3f

        private val KEY_DENSITY_SCALE = floatPreferencesKey("density_scale")
    }
}
