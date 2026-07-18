package com.openwave.music.features.profile

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.openwave.music.core.domain.UserProfile
import com.openwave.music.features.UserProfileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_profile",
)

@Singleton
class UserProfileRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : UserProfileRepository {

    private val nameKey = stringPreferencesKey("display_name")
    private val avatarKey = stringPreferencesKey("avatar_uri")

    override val profile: Flow<UserProfile> = context.profileDataStore.data.map { prefs ->
        val name = prefs[nameKey]?.trim().orEmpty()
        UserProfile(
            displayName = name.ifBlank { DEFAULT_NAME },
            avatarUri = prefs[avatarKey]?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun updateDisplayName(name: String) {
        val cleaned = name.trim().ifBlank { DEFAULT_NAME }
        context.profileDataStore.edit { it[nameKey] = cleaned }
    }

    override suspend fun updateAvatarUri(uri: String?) {
        context.profileDataStore.edit { prefs ->
            if (uri.isNullOrBlank()) prefs.remove(avatarKey)
            else prefs[avatarKey] = uri
        }
    }

    companion object {
        const val DEFAULT_NAME = "Người dùng"
    }
}
