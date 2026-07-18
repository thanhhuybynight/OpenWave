package com.openwave.music.features.profile

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.openwave.music.core.domain.UserProfile
import com.openwave.music.features.UserProfileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
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
            avatarUri = prefs[avatarKey]?.takeIf { it.isNotBlank() && avatarFileExists(it) },
        )
    }

    override suspend fun updateDisplayName(name: String) {
        val cleaned = name.trim().ifBlank { DEFAULT_NAME }
        context.profileDataStore.edit { it[nameKey] = cleaned }
    }

    /**
     * Persist avatar under app filesDir. Content URIs from the picker expire
     * after permission revoke / reboot, so we copy bytes once.
     */
    override suspend fun updateAvatarUri(uri: String?) = withContext(Dispatchers.IO) {
        val dest = avatarFile()
        if (uri.isNullOrBlank()) {
            dest.delete()
            context.profileDataStore.edit { it.remove(avatarKey) }
            return@withContext
        }
        val parsed = Uri.parse(uri)
        val copied = runCatching {
            context.contentResolver.openInputStream(parsed)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } != null && dest.exists() && dest.length() > 0L
        }.getOrDefault(false)
        if (!copied) {
            // Already a durable file:// path we own
            if (uri.startsWith("file:") || File(uri).exists()) {
                val src = if (uri.startsWith("file:")) File(Uri.parse(uri).path!!) else File(uri)
                if (src.absolutePath != dest.absolutePath && src.exists()) {
                    src.copyTo(dest, overwrite = true)
                }
            } else {
                error("Could not read avatar")
            }
        }
        val stable = Uri.fromFile(dest).toString()
        context.profileDataStore.edit { it[avatarKey] = stable }
    }

    private fun avatarFile(): File = File(context.filesDir, AVATAR_NAME)

    private fun avatarFileExists(stored: String): Boolean {
        return try {
            when {
                stored.startsWith("file:") -> {
                    val path = Uri.parse(stored).path ?: return false
                    File(path).exists()
                }
                stored.startsWith("content:") -> false // ephemeral — treat as missing
                else -> File(stored).exists()
            }
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        const val DEFAULT_NAME = "Người dùng"
        private const val AVATAR_NAME = "profile_avatar.jpg"
    }
}
