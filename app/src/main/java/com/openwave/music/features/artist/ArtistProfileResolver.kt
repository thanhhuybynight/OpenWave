package com.openwave.music.features.artist

import android.util.Log
import com.openwave.music.core.domain.RecentArtist
import com.openwave.music.data.source.newpipe.NewPipeBootstrap
import com.openwave.music.data.source.youtube.YtmCreditsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolve performer credits to real YouTube Music artist profiles.
 *
 * Rules:
 * - Prefer known channel/browse id (from YTM credits) — never invent the first search hit.
 * - Name-only resolve uses YTM search + exact name match + MUSIC_PAGE_TYPE_ARTIST.
 */
@Singleton
class ArtistProfileResolver @Inject constructor(
    private val newPipe: NewPipeBootstrap,
    private val http: OkHttpClient,
    private val credits: YtmCreditsClient,
) {
    data class Resolved(
        val name: String,
        val channelId: String?,
        val imageUrl: String?,
    )

    private val byChannel = ConcurrentHashMap<String, Resolved>()
    private val byName = ConcurrentHashMap<String, Resolved>()
    private val mutex = Mutex()

    suspend fun enrich(artists: List<RecentArtist>): List<RecentArtist> =
        withContext(Dispatchers.IO) {
            if (artists.isEmpty()) return@withContext artists
            newPipe.ensureInit()
            coroutineScope {
                artists.map { a ->
                    async {
                        val resolved = when {
                            !a.channelId.isNullOrBlank() -> resolveChannel(a.channelId)
                            else -> resolveExactName(a.name)
                        }
                        if (resolved == null) a
                        else a.copy(
                            name = resolved.name.ifBlank { a.name },
                            coverUrl = resolved.imageUrl ?: a.coverUrl,
                            channelId = resolved.channelId ?: a.channelId,
                        )
                    }
                }.awaitAll()
            }
        }

    /**
     * Prefer credits tied to a video (performers on that track), not a loose name search.
     */
    suspend fun enrichFromVideo(videoId: String, fallbackNames: List<String>): List<RecentArtist> =
        withContext(Dispatchers.IO) {
            val credited = credits.artistsForVideo(videoId)
            if (credited.isNotEmpty()) {
                return@withContext credited.map {
                    RecentArtist(
                        name = it.name,
                        lastPlayedAtMs = 0L,
                        playCount = 1,
                        channelId = it.id.takeIf { id -> id.startsWith("UC") },
                    )
                }.let { enrich(it) }
            }
            enrich(
                fallbackNames.map {
                    RecentArtist(name = it, lastPlayedAtMs = 0L, playCount = 1)
                },
            )
        }

    suspend fun resolveChannel(channelId: String): Resolved? {
        val id = channelId.trim()
        if (id.isBlank()) return null
        byChannel[id]?.let { return it }
        return mutex.withLock {
            byChannel[id]?.let { return it }
            val found = runCatching { loadChannel(id) }
                .onFailure { Log.w(TAG, "channel $id: ${it.message}") }
                .getOrNull()
            if (found != null) {
                byChannel[id] = found
                byName[found.name.lowercase(Locale.US)] = found
            }
            found
        }
    }

    suspend fun resolveExactName(name: String): Resolved? {
        val key = name.trim().lowercase(Locale.US)
        if (key.isBlank()) return null
        byName[key]?.let { return it }
        return mutex.withLock {
            byName[key]?.let { return it }
            val found = runCatching { searchExactArtist(name.trim()) }
                .onFailure { Log.w(TAG, "name '$name': ${it.message}") }
                .getOrNull()
            if (found != null) {
                byName[key] = found
                found.channelId?.let { byChannel[it] = found }
            }
            found
        }
    }

    private fun loadChannel(channelId: String): Resolved? {
        // Prefer music.youtube.com channel page when UC…
        val urls = listOf(
            "https://www.youtube.com/channel/$channelId",
            "https://music.youtube.com/channel/$channelId",
        )
        for (url in urls) {
            val ch = runCatching { ChannelInfo.getInfo(ServiceList.YouTube, url) }.getOrNull()
                ?: continue
            val avatar = ch.avatars?.maxByOrNull { it.height * it.width }?.url
                ?: ch.avatars?.firstOrNull()?.url
            return Resolved(
                name = ch.name.orEmpty().ifBlank { channelId },
                channelId = channelId.takeIf { it.startsWith("UC") } ?: extractChannelId(ch.url),
                imageUrl = avatar,
            )
        }
        return null
    }

    /**
     * YTM search — only accept MUSIC_PAGE_TYPE_ARTIST with exact (case-insensitive) name.
     * Never return the first random hit.
     */
    private fun searchExactArtist(name: String): Resolved? {
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB_REMIX")
                        .put("clientVersion", CLIENT_VERSION)
                        .put("hl", "en")
                        .put("gl", "US"),
                ),
            )
            .put("query", name)
            // artists filter param (music_artists)
            .put("params", "EgWKAQIgAWoKEAMQBBAJEAoQBQ%3D%3D")
            .toString()
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            .post(body.toRequestBody(JSON))
            .header("User-Agent", UA)
            .header("Content-Type", "application/json")
            .header("X-Goog-Api-Format-Version", "1")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .build()
        val root = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            JSONObject(resp.body?.string().orEmpty())
        }
        val items = ArrayList<JSONObject>()
        collectKey(root, "musicResponsiveListItemRenderer", items)
        // Also top-level musicTwoRowItemRenderer for artist cards
        collectKey(root, "musicTwoRowItemRenderer", items)

        val want = name.trim().lowercase(Locale.US)
        for (item in items) {
            val hit = parseArtistCard(item) ?: continue
            if (hit.name.trim().lowercase(Locale.US) != want) continue
            // exact match only
            val withAvatar = hit.channelId?.let { cid ->
                loadChannel(cid)?.let { loaded ->
                    hit.copy(imageUrl = loaded.imageUrl ?: hit.imageUrl, name = loaded.name)
                }
            } ?: hit
            return withAvatar
        }
        Log.d(TAG, "no exact YTM artist for '$name'")
        return null
    }

    private fun parseArtistCard(item: JSONObject): Resolved? {
        // Two-row artist shelf
        val title = item.optJSONObject("title")
        val nameFromTitle = title?.optString("text")?.takeIf { it.isNotBlank() }
            ?: runsText(title?.optJSONArray("runs"))
        val browse = item.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?: item.optJSONObject("onTap")
                ?.optJSONObject("innertubeCommand")
                ?.optJSONObject("browseEndpoint")
        val pageType = browse
            ?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType")
            .orEmpty()
        if (browse != null && (pageType.isEmpty() || pageType == "MUSIC_PAGE_TYPE_ARTIST")) {
            val browseId = browse.optString("browseId")
            if (browseId.startsWith("UC") || pageType == "MUSIC_PAGE_TYPE_ARTIST") {
                val n = nameFromTitle
                    ?: textFromFlex(item.optJSONArray("flexColumns")?.optJSONObject(0))
                    ?: return null
                val thumb = item.optJSONObject("thumbnailRenderer")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
                    ?: item.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                return Resolved(
                    name = n,
                    channelId = browseId.takeIf { it.startsWith("UC") },
                    imageUrl = bestThumb(thumb),
                )
            }
        }

        // Responsive list: first flex = name, subtitle may say "Artist"
        val flex = item.optJSONArray("flexColumns") ?: return null
        val name = textFromFlex(flex.optJSONObject(0)) ?: return null
        // Find artist browse in subtitle runs or menu
        val subtitleRuns = runsFromFlex(flex.optJSONObject(1))
        val isArtistRow = subtitleRuns.any {
            it.optString("text").contains("Artist", ignoreCase = true)
        } || pageType == "MUSIC_PAGE_TYPE_ARTIST"
        val navBrowse = item.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
        val bid = navBrowse?.optString("browseId").orEmpty()
        if (bid.startsWith("UC") && (isArtistRow || pageType == "MUSIC_PAGE_TYPE_ARTIST")) {
            return Resolved(name = name, channelId = bid, imageUrl = null)
        }
        return null
    }

    private fun textFromFlex(flexCol: JSONObject?): String? {
        val text = flexCol
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?: return null
        text.optString("text").takeIf { it.isNotBlank() }?.let { return it }
        return runsText(text.optJSONArray("runs"))
    }

    private fun runsFromFlex(flexCol: JSONObject?): List<JSONObject> {
        val runs = flexCol
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?: return emptyList()
        return buildList {
            for (i in 0 until runs.length()) runs.optJSONObject(i)?.let { add(it) }
        }
    }

    private fun runsText(runs: JSONArray?): String? {
        if (runs == null) return null
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun bestThumb(arr: JSONArray?): String? {
        if (arr == null || arr.length() == 0) return null
        var best: String? = null
        var bestW = -1
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val url = t.optString("url").takeIf { it.startsWith("http") } ?: continue
            val w = t.optInt("width", 0)
            if (w >= bestW) {
                bestW = w
                best = url
            }
        }
        return best
    }

    private fun collectKey(o: Any?, key: String, out: MutableList<JSONObject>) {
        when (o) {
            is JSONObject -> {
                o.optJSONObject(key)?.let { out += it }
                val keys = o.keys()
                while (keys.hasNext()) collectKey(o.opt(keys.next()), key, out)
            }
            is JSONArray -> for (i in 0 until o.length()) collectKey(o.opt(i), key, out)
        }
    }

    private fun extractChannelId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        Regex("channel/(UC[\\w-]{20,})").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    companion object {
        private const val TAG = "ArtistProfile"
        private const val CLIENT_VERSION = "1.20241204.01.00"
        private val JSON = "application/json".toMediaType()
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
