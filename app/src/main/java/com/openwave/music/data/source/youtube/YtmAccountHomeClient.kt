package com.openwave.music.data.source.youtube

import android.webkit.CookieManager
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.ArtworkUrls
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.Track
import com.openwave.music.features.settings.YouTubeAccount
import com.openwave.music.features.settings.YouTubeSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class AccountHomePage(val tracks: List<Track>, val continuation: String?)

@Singleton
class YtmAccountHomeClient @Inject constructor(
    private val http: OkHttpClient,
    private val session: YouTubeSessionStore,
) {
    suspend fun account(): YouTubeAccount? = withContext(Dispatchers.IO) {
        val root = request("account/account_menu", JSONObject()) ?: return@withContext null
        val names = mutableListOf<Any>()
        collectValues(root, "accountName", names)
        val name = names.firstNotNullOfOrNull(::text) ?: return@withContext null
        val thumbnails = mutableListOf<Any>()
        collectValues(root, "thumbnails", thumbnails)
        val avatar = thumbnails.asSequence().filterIsInstance<JSONArray>()
            .mapNotNull(::bestThumb).firstOrNull()
        YouTubeAccount(name, avatar).also { session.saveAccount(it) }
    }

    suspend fun recommendations(continuation: String? = null, limit: Int = 24): AccountHomePage =
        withContext(Dispatchers.IO) {
            val body = if (continuation == null) JSONObject().put("browseId", "FEmusic_home")
            else JSONObject().put("continuation", continuation)
            val root = request("browse", body) ?: return@withContext AccountHomePage(emptyList(), null)
            val listItems = mutableListOf<JSONObject>()
            val twoRows = mutableListOf<JSONObject>()
            collect(root, "musicResponsiveListItemRenderer", listItems)
            collect(root, "musicTwoRowItemRenderer", twoRows)
            val commands = mutableListOf<JSONObject>()
            collect(root, "continuationCommand", commands)
            val continuationData = mutableListOf<JSONObject>()
            collect(root, "nextContinuationData", continuationData)
            AccountHomePage(
                tracks = (listItems.mapNotNull(::parseTrack) + twoRows.mapNotNull(::parseTwoRowTrack))
                    .distinctBy { it.id }.take(limit),
                continuation = commands.firstNotNullOfOrNull {
                    it.optString("token").takeIf(String::isNotBlank)
                } ?: continuationData.firstNotNullOfOrNull {
                    it.optString("continuation").takeIf(String::isNotBlank)
                },
            )
        }

    private fun request(endpoint: String, payload: JSONObject): JSONObject? {
        val cookie = CookieManager.getInstance().getCookie(YouTubeSessionStore.YOUTUBE_MUSIC_URL).orEmpty()
        val sapisid = cookie.split(';').map { it.trim() }.firstNotNullOfOrNull { part ->
            part.substringAfter('=', "").takeIf {
                part.substringBefore('=') in setOf("SAPISID", "__Secure-3PAPISID")
            }
        } ?: return null
        val timestamp = System.currentTimeMillis() / 1000
        val hash = MessageDigest.getInstance("SHA-1")
            .digest("$timestamp $sapisid $ORIGIN".toByteArray())
            .joinToString("") { "%02x".format(it) }
        payload.put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB_REMIX").put("clientVersion", CLIENT_VERSION)
            .put("hl", "vi").put("gl", "VN")))
        val request = Request.Builder().url("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON)).header("Cookie", cookie)
            .header("Authorization", "SAPISIDHASH ${timestamp}_$hash").header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN).header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", CLIENT_VERSION).build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) return null
            if (!response.isSuccessful) error("YouTube $endpoint HTTP ${response.code}")
            JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun parseTrack(item: JSONObject): Track? {
        val videoId = findVideoId(item) ?: return null
        val columns = item.optJSONArray("flexColumns") ?: return null
        val title = runs(columns.optJSONObject(0)).joinToString("") { it.optString("text") }.trim()
            .takeIf { it.isNotBlank() } ?: return null
        val artists = artists(runs(columns.optJSONObject(1)))
        if (artists.isEmpty()) return null
        val thumbs = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        return track(videoId, title, artists, thumbs)
    }

    private fun parseTwoRowTrack(item: JSONObject): Track? {
        val videoId = findVideoId(item) ?: return null
        val title = item.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)
            ?.optString("text")?.takeIf { it.isNotBlank() } ?: return null
        val artists = artists(item.optJSONObject("subtitle")?.optJSONArray("runs").objects())
        if (artists.isEmpty()) return null
        val thumbs = item.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        return track(videoId, title, artists, thumbs)
    }

    private fun track(id: String, title: String, artists: List<Artist>, thumbs: JSONArray?) = Track(
        id = id, title = title, artists = artists, source = MusicSource.YOUTUBE_MUSIC,
        coverUrl = ArtworkUrls.highRes(bestThumb(thumbs), id),
        sourceUri = "https://music.youtube.com/watch?v=$id",
    )

    private fun artists(runs: List<JSONObject>) = runs.mapNotNull { run ->
        val browse = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
        val type = browse?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
        if (type == "MUSIC_PAGE_TYPE_ARTIST") Artist(browse.optString("browseId"), run.optString("text"), MusicSource.YOUTUBE_MUSIC) else null
    }.distinctBy { it.id }

    private fun runs(column: JSONObject?): List<JSONObject> = column
        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
        ?.optJSONArray("runs").objects()

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList()
        else buildList { for (i in 0 until length()) optJSONObject(i)?.let(::add) }

    private fun text(value: Any?): String? = when (value) {
        is String -> value.takeIf(String::isNotBlank)
        is JSONObject -> value.optString("simpleText").takeIf(String::isNotBlank)
            ?: value.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.takeIf(String::isNotBlank)
        else -> null
    }

    private fun findVideoId(value: Any?): String? {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("watchEndpoint")?.optString("videoId")
                    ?.takeIf { it.length == 11 }?.let { return it }
                val keys = value.keys()
                while (keys.hasNext()) findVideoId(value.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> for (i in 0 until value.length()) findVideoId(value.opt(i))?.let { return it }
        }
        return null
    }

    private fun collect(value: Any?, key: String, output: MutableList<JSONObject>) {
        when (value) {
            is JSONObject -> {
                value.optJSONObject(key)?.let(output::add)
                val keys = value.keys()
                while (keys.hasNext()) collect(value.opt(keys.next()), key, output)
            }
            is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i), key, output)
        }
    }

    private fun collectValues(value: Any?, key: String, output: MutableList<Any>) {
        when (value) {
            is JSONObject -> {
                if (value.has(key)) value.opt(key)?.let(output::add)
                val keys = value.keys()
                while (keys.hasNext()) collectValues(value.opt(keys.next()), key, output)
            }
            is JSONArray -> for (i in 0 until value.length()) collectValues(value.opt(i), key, output)
        }
    }

    private fun bestThumb(array: JSONArray?): String? = array.objects()
        .maxByOrNull { it.optInt("width") }?.optString("url")

    companion object {
        private const val ORIGIN = "https://music.youtube.com"
        private const val CLIENT_VERSION = "1.20241204.01.00"
        private val JSON = "application/json".toMediaType()
    }
}
