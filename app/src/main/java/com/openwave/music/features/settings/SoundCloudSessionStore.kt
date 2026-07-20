package com.openwave.music.features.settings

import android.content.Context
import android.webkit.CookieManager
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

private val Context.soundCloudSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "soundcloud_session",
)

@Singleton
class SoundCloudSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val loggedIn: Flow<Boolean> = context.soundCloudSessionDataStore.data.map {
        it[KEY_LOGGED_IN] == true
    }

    fun oauthToken(): String? = CookieManager.getInstance().getCookie(SOUNDCLOUD_URL).orEmpty()
        .split(';')
        .firstNotNullOfOrNull { part ->
            part.trim().takeIf { it.substringBefore('=') == "oauth_token" }
                ?.substringAfter('=', "")?.takeIf(String::isNotBlank)
        }

    suspend fun setLoggedIn(loggedIn: Boolean) {
        CookieManager.getInstance().flush()
        context.soundCloudSessionDataStore.edit { preferences ->
            if (loggedIn) preferences[KEY_LOGGED_IN] = true else preferences.clear()
        }
    }

    suspend fun logout() {
        val cookies = CookieManager.getInstance()
        listOf(SOUNDCLOUD_URL, API_URL).forEach { url ->
            cookies.setCookie(url, "oauth_token=; Max-Age=0; Path=/; Secure")
        }
        cookies.flush()
        context.soundCloudSessionDataStore.edit { it.clear() }
    }

    companion object {
        const val SOUNDCLOUD_URL = "https://soundcloud.com/"
        const val API_URL = "https://api-v2.soundcloud.com/"
        const val LOGIN_URL = "https://soundcloud.com/signin"
        private val KEY_LOGGED_IN = booleanPreferencesKey("logged_in")
    }
}
