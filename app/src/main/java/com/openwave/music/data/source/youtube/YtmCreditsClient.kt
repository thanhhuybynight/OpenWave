package com.openwave.music.data.source.youtube

import android.util.Log
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube Music WEB_REMIX credits extraction.
 *
 * NewPipe only exposes the **first** flex-column artist; YTM JSON actually lists
 * every performer as a separate `runs[]` entry with `MUSIC_PAGE_TYPE_ARTIST` +
 * `browseId` (channel). This client parses those runs for search + /next.
 */
@Singleton
class YtmCreditsClient @Inject constructor(
    private val http: OkHttpClient,
) {
    private val byVideo = ConcurrentHashMap<String, List<Artist>>()

    /**
     * Artists credited on a specific video (performers), ordered as on YTM.
     */
    suspend fun artistsForVideo(videoId: String): List<Artist> = withContext(Dispatchers.IO) {
        val id = YouTubeMusicSourceClient.normalizeId(videoId)
        byVideo[id]?.let { return@withContext it }
        val artists = runCatching { fetchNextArtists(id) }
            .onFailure { Log.w(TAG, "next credits $id: ${it.message}") }
            .getOrDefault(emptyList())
        if (artists.isNotEmpty()) byVideo[id] = artists
        artists
    }

    /**
     * YTM music search with full multi-artist credits per hit.
     */
    suspend fun searchSongs(query: String, limit: Int = 20): List<Track> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            runCatching { fetchSearch(q, limit) }
                .onFailure { Log.w(TAG, "search: ${it.message}") }
                .getOrDefault(emptyList())
        }

    private fun fetchNextArtists(videoId: String): List<Artist> {
        val body = baseContext()
            .put("videoId", videoId)
            .put("isAudioOnly", true)
            .toString()
        val root = post("https://music.youtube.com/youtubei/v1/next?prettyPrint=false", body)
            ?: return emptyList()
        // Collect EVERY longBylineText artist run (performers), ignore album/topic rows
        val artists = ArrayList<Artist>()
        findLongBylineRuns(root).forEach { run ->
            parseArtistRun(run)?.let { artists += it }
        }
        // Also harvest any MUSIC_PAGE_TYPE_ARTIST runs elsewhere (watch header variants)
        if (artists.isEmpty()) {
            findAllArtistRuns(root).forEach { run ->
                parseArtistRun(run)?.let { artists += it }
            }
        }
        val deduped = artists.distinctBy {
            it.id.takeIf { id -> id.startsWith("UC") } ?: it.name.lowercase()
        }
        if (deduped.isNotEmpty()) {
            Log.i(TAG, "next $videoId performers=${deduped.map { "${it.name}/${it.id}" }}")
        } else {
            Log.w(TAG, "next $videoId: no MUSIC_PAGE_TYPE_ARTIST runs")
        }
        return deduped
    }

    /** Walk entire next payload for runs that browse to an artist page. */
    private fun findAllArtistRuns(root: JSONObject): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun walk(o: Any?) {
            when (o) {
                is JSONObject -> {
                    // A "run" object: { text, navigationEndpoint.browseEndpoint... }
                    if (o.has("text") && o.has("navigationEndpoint")) {
                        val pageType = o.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("browseEndpoint")
                            ?.optJSONObject("browseEndpointContextSupportedConfigs")
                            ?.optJSONObject("browseEndpointContextMusicConfig")
                            ?.optString("pageType")
                            .orEmpty()
                        if (pageType == "MUSIC_PAGE_TYPE_ARTIST") {
                            out += o
                        }
                    }
                    val keys = o.keys()
                    while (keys.hasNext()) walk(o.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
            }
        }
        walk(root)
        return out
    }

    private fun fetchSearch(query: String, limit: Int): List<Track> {
        val body = baseContext()
            .put("query", query)
            // Filter ≈ songs tab (same param NewPipe uses for music_songs)
            .put("params", "EgWKAQIIAWoKEAMQBBAJEAoQBQ%3D%3D")
            .toString()
        val root = post("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", body)
            ?: return emptyList()
        val items = ArrayList<JSONObject>()
        collectRenderers(root, "musicResponsiveListItemRenderer", items)
        val out = ArrayList<Track>()
        for (item in items) {
            if (out.size >= limit) break
            parseSearchItem(item)?.let { t ->
                out += t
                // Warm video→artists cache
                if (t.artists.isNotEmpty()) byVideo[t.id] = t.artists
            }
        }
        Log.d(TAG, "search '$query' -> ${out.size} tracks")
        return out
    }

    private fun parseSearchItem(item: JSONObject): Track? {
        val videoId = item.optJSONObject("playlistItemData")?.optString("videoId")
            ?.takeIf { it.isNotBlank() }
            ?: extractWatchVideoId(item)
            ?: return null
        val flex = item.optJSONArray("flexColumns") ?: return null
        val title = textFromFlex(flex.optJSONObject(0)) ?: return null
        val creditRuns = runsFromFlex(flex.optJSONObject(1))
        val artists = creditRuns.mapNotNull { parseArtistRun(it) }
            .distinctBy { it.id.ifBlank { it.name.lowercase() } }
        if (artists.isEmpty()) return null
        val thumbs = item.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val cover = bestThumb(thumbs)
        return Track(
            id = videoId,
            title = title,
            artists = artists,
            source = MusicSource.YOUTUBE_MUSIC,
            coverUrl = cover,
            sourceUri = "https://music.youtube.com/watch?v=$videoId",
        )
    }

    private fun parseArtistRun(run: JSONObject): Artist? {
        val name = run.optString("text").trim()
        if (name.isBlank()) return null
        // Separators like " & ", " • " have no navigationEndpoint
        val browse = run.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?: return null
        val pageType = browse
            .optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType")
            .orEmpty()
        if (pageType != "MUSIC_PAGE_TYPE_ARTIST") return null
        val browseId = browse.optString("browseId").takeIf { it.isNotBlank() } ?: return null
        // Channel ids are UC…; some artists use other browse ids — still useful as id
        return Artist(
            id = browseId,
            name = name,
            source = MusicSource.YOUTUBE_MUSIC,
        )
    }

    private fun findLongBylineRuns(root: JSONObject): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun walk(o: Any?) {
            when (o) {
                is JSONObject -> {
                    val lb = o.optJSONObject("longBylineText")
                    if (lb != null) {
                        val runs = lb.optJSONArray("runs")
                        if (runs != null) {
                            for (i in 0 until runs.length()) {
                                runs.optJSONObject(i)?.let { out += it }
                            }
                        }
                    }
                    val keys = o.keys()
                    while (keys.hasNext()) walk(o.opt(keys.next()))
                }
                is JSONArray -> {
                    for (i in 0 until o.length()) walk(o.opt(i))
                }
            }
        }
        walk(root)
        return out
    }

    private fun collectRenderers(o: Any?, key: String, out: MutableList<JSONObject>) {
        when (o) {
            is JSONObject -> {
                o.optJSONObject(key)?.let { out += it }
                val keys = o.keys()
                while (keys.hasNext()) collectRenderers(o.opt(keys.next()), key, out)
            }
            is JSONArray -> {
                for (i in 0 until o.length()) collectRenderers(o.opt(i), key, out)
            }
        }
    }

    private fun textFromFlex(flexCol: JSONObject?): String? {
        val text = flexCol
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?: return null
        text.optString("text").takeIf { it.isNotBlank() }?.let { return it }
        val runs = text.optJSONArray("runs") ?: return null
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun runsFromFlex(flexCol: JSONObject?): List<JSONObject> {
        val runs = flexCol
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?: return emptyList()
        return buildList {
            for (i in 0 until runs.length()) {
                runs.optJSONObject(i)?.let { add(it) }
            }
        }
    }

    private fun extractWatchVideoId(item: JSONObject): String? {
        // overlay / doubleTap / navigationEndpoint watchEndpoint
        fun find(o: Any?): String? {
            when (o) {
                is JSONObject -> {
                    o.optJSONObject("watchEndpoint")?.optString("videoId")
                        ?.takeIf { it.length == 11 }?.let { return it }
                    val keys = o.keys()
                    while (keys.hasNext()) {
                        find(o.opt(keys.next()))?.let { return it }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until o.length()) find(o.opt(i))?.let { return it }
                }
            }
            return null
        }
        return find(item)
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

    private fun baseContext(): JSONObject =
        JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", CLIENT_NAME)
                        .put("clientVersion", CLIENT_VERSION)
                        .put("hl", "en")
                        .put("gl", "US")
                        .put("userAgent", UA)
                        .put("platform", "DESKTOP"),
                ),
            )

    private fun post(url: String, jsonBody: String): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON))
            .header("User-Agent", UA)
            .header("Content-Type", "application/json")
            .header("X-Goog-Api-Format-Version", "1")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .build()
        return http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} $url")
                null
            } else {
                JSONObject(text)
            }
        }
    }

    companion object {
        private const val TAG = "YtmCredits"
        private const val CLIENT_NAME = "WEB_REMIX"
        private const val CLIENT_VERSION = "1.20241204.01.00"
        private val JSON = "application/json".toMediaType()
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
