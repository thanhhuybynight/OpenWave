package com.openwave.music.data.source.soundcloud

import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.Playlist
import com.openwave.music.core.domain.Track
import com.openwave.music.features.settings.SoundCloudSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class SoundCloudAccount(val id: String, val name: String, val avatarUrl: String?)

@Singleton
class SoundCloudAccountClient @Inject constructor(
    private val http: OkHttpClient,
    private val session: SoundCloudSessionStore,
    private val source: SoundCloudSourceClient,
) {
    suspend fun account(): SoundCloudAccount? = withContext(Dispatchers.IO) {
        getJson("https://api-v2.soundcloud.com/me")?.let(::parseAccount)
    }

    suspend fun likedTracks(userId: String): List<Track> = withContext(Dispatchers.IO) {
        paged("https://api-v2.soundcloud.com/users/$userId/track_likes?limit=$PAGE_SIZE&linked_partitioning=1")
            .mapNotNull { source.parseTrack(it.optJSONObject("track") ?: it) }
    }

    suspend fun playlists(userId: String): List<Playlist> = withContext(Dispatchers.IO) {
        paged("https://api-v2.soundcloud.com/users/$userId/playlists?limit=$PAGE_SIZE&linked_partitioning=1")
            .mapNotNull(::parsePlaylist)
    }

    suspend fun playlistTracks(id: String): List<Track> = withContext(Dispatchers.IO) {
        val root = getJson("https://api-v2.soundcloud.com/playlists/$id")
            ?: error("Không tải được playlist SoundCloud")
        val tracks = root.optJSONArray("tracks") ?: JSONArray()
        buildList {
            for (i in 0 until minOf(tracks.length(), MAX_ITEMS)) {
                source.parseTrack(tracks.optJSONObject(i))?.let(::add)
            }
        }
    }

    private fun paged(initialUrl: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var next: String? = initialUrl
        var pages = 0
        while (next != null && pages++ < MAX_PAGES && result.size < MAX_ITEMS) {
            val root = getJson(next) ?: error("Không tải được thư viện SoundCloud")
            val collection = root.optJSONArray("collection") ?: JSONArray()
            for (i in 0 until collection.length()) {
                collection.optJSONObject(i)?.let(result::add)
                if (result.size == MAX_ITEMS) break
            }
            next = root.optString("next_href")
                .takeIf { it.startsWith("https://api-v2.soundcloud.com/") }
        }
        return result
    }

    private fun getJson(url: String): JSONObject? {
        val token = session.oauthToken() ?: return null
        val request = Request.Builder().url(source.withClientId(url))
            .header("Authorization", "OAuth $token")
            .header("Accept", "application/json")
            .header("Origin", "https://soundcloud.com")
            .header("Referer", "https://soundcloud.com/")
            .build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) return null
            if (!response.isSuccessful) error("SoundCloud HTTP ${response.code}")
            response.body?.string()?.let(::JSONObject)
        }
    }

    private fun parseAccount(root: JSONObject): SoundCloudAccount? {
        val id = root.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val name = root.optString("username").ifBlank { root.optString("full_name") }
            .takeIf { it.isNotBlank() } ?: return null
        return SoundCloudAccount(id, name, root.optString("avatar_url").takeIf { it.startsWith("http") })
    }

    private fun parsePlaylist(root: JSONObject): Playlist? {
        val id = root.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val title = root.optString("title").takeIf { it.isNotBlank() } ?: return null
        val tracks = root.optJSONArray("tracks") ?: JSONArray()
        return Playlist(
            id = id,
            title = title,
            description = root.optString("description").takeIf { it.isNotBlank() },
            coverUrl = root.optString("artwork_url").takeIf { it.startsWith("http") },
            tracks = buildList {
                for (i in 0 until minOf(tracks.length(), MAX_ITEMS)) {
                    source.parseTrack(tracks.optJSONObject(i))?.let(::add)
                }
            },
            source = MusicSource.SOUNDCLOUD,
        )
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 5
        const val MAX_ITEMS = 500
    }
}
