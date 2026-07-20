package com.openwave.music.features.settings

import android.content.Context
import android.webkit.CookieManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.youtubeSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "youtube_session",
)

data class YouTubeAccount(val name: String, val avatarUrl: String?)

@Singleton
class YouTubeSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val loggedIn: Flow<Boolean> = context.youtubeSessionDataStore.data.map {
        it[KEY_LOGGED_IN] == true
    }
    val account: Flow<YouTubeAccount?> = context.youtubeSessionDataStore.data.map { preferences ->
        preferences[KEY_ACCOUNT_NAME]?.let {
            YouTubeAccount(it, preferences[KEY_ACCOUNT_AVATAR])
        }
    }

    suspend fun finishLogin(): Boolean {
        val valid = CookieManager.getInstance().getCookie(YOUTUBE_MUSIC_URL).orEmpty()
            .split(';').any {
                val name = it.substringBefore('=').trim()
                name == "SAPISID" || name == "__Secure-3PAPISID"
            }
        if (valid) {
            CookieManager.getInstance().flush()
            context.youtubeSessionDataStore.edit { it[KEY_LOGGED_IN] = true }
        }
        return valid
    }

    suspend fun saveAccount(account: YouTubeAccount) {
        context.youtubeSessionDataStore.edit {
            it[KEY_ACCOUNT_NAME] = account.name
            account.avatarUrl?.let { avatar -> it[KEY_ACCOUNT_AVATAR] = avatar }
                ?: it.remove(KEY_ACCOUNT_AVATAR)
        }
    }

    suspend fun logout() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        context.youtubeSessionDataStore.edit { it.clear() }
    }

    companion object {
        const val YOUTUBE_MUSIC_URL = "https://music.youtube.com/"
        const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&uilel=3" +
                "&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin" +
                "%3Dtrue%26app%3Ddesktop%26hl%3Den%26next%3Dhttps%253A%252F%252Fmusic.youtube.com" +
                "%252F%26feature%3D__FEATURE__&hl=en"

        private val KEY_LOGGED_IN = booleanPreferencesKey("logged_in")
        private val KEY_ACCOUNT_NAME = stringPreferencesKey("account_name")
        private val KEY_ACCOUNT_AVATAR = stringPreferencesKey("account_avatar")
    }
}
