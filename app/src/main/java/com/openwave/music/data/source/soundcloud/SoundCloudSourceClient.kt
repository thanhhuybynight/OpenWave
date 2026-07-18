package com.openwave.music.data.source.soundcloud

import android.util.Log
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.MusicSourceClient
import com.openwave.music.core.domain.SearchResult
import com.openwave.music.core.domain.StreamInfo
import com.openwave.music.core.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SoundCloud API v2.
 * client_id is scraped from web JS as `client_id:"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"`.
 */
@Singleton
class SoundCloudSourceClient @Inject constructor(
    baseHttp: OkHttpClient,
) : MusicSourceClient {

    override val source: MusicSource = MusicSource.SOUNDCLOUD
    override val supportsAnonymousPlayback: Boolean = true

    /** Dedicated client: longer timeouts for multi-script discovery. */
    private val http: OkHttpClient = baseHttp.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val clientId = AtomicReference<String?>(null)

    override suspend fun search(query: String, limit: Int): SearchResult =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext SearchResult()
            try {
                val cid = requireClientId()
                val url =
                    "https://api-v2.soundcloud.com/search/tracks?q=${q.encode()}" +
                        "&client_id=$cid&limit=$limit&offset=0&app_locale=en"
                val body = get(url) ?: error("empty search body")
                val arr = JSONObject(body).optJSONArray("collection") ?: JSONArray()
                val tracks = buildList {
                    for (i in 0 until arr.length()) {
                        parseTrack(arr.getJSONObject(i))?.let { add(it) }
                    }
                }
                if (tracks.isEmpty()) {
                    Log.w(TAG, "search empty for q=$q")
                }
                SearchResult(tracks = tracks)
            } catch (t: Throwable) {
                Log.e(TAG, "search failed: ${t.message}", t)
                // Do NOT silently mix demo SC tracks as if they were real SC results
                SearchResult(tracks = emptyList())
            }
        }

    override suspend fun getTrack(id: String): Track? = withContext(Dispatchers.IO) {
        try {
            val cid = requireClientId()
            val numeric = id.removePrefix("sc:")
            val body = get("https://api-v2.soundcloud.com/tracks/$numeric?client_id=$cid")
                ?: return@withContext null
            parseTrack(JSONObject(body))
        } catch (t: Throwable) {
            Log.e(TAG, "getTrack: ${t.message}")
            null
        }
    }

    /**
     * Related / station-style tracks for a seed (SoundCloud radio).
     * GET /tracks/{id}/related
     */
    suspend fun relatedTracks(trackId: String, limit: Int = 24): List<Track> =
        withContext(Dispatchers.IO) {
            try {
                val cid = requireClientId()
                val numeric = trackId.removePrefix("sc:")
                val url =
                    "https://api-v2.soundcloud.com/tracks/$numeric/related" +
                        "?client_id=$cid&limit=$limit&offset=0&app_locale=en"
                val body = get(url) ?: return@withContext emptyList()
                val root = JSONObject(body)
                val arr = root.optJSONArray("collection")
                    ?: root.optJSONArray("tracks")
                    ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        parseTrack(arr.getJSONObject(i))?.let { add(it) }
                    }
                }.also {
                    Log.d(TAG, "related ${it.size} for sc:$numeric")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "relatedTracks: ${t.message}", t)
                emptyList()
            }
        }

    override suspend fun getStream(track: Track): StreamInfo? = withContext(Dispatchers.IO) {
        // Local demo URLs only when explicitly local
        if (track.source == MusicSource.LOCAL &&
            !track.streamUrl.isNullOrBlank() &&
            track.streamUrl!!.startsWith("http")
        ) {
            return@withContext StreamInfo(url = track.streamUrl!!, mimeType = "audio/mpeg")
        }
        try {
            val cid = requireClientId()
            val numeric = track.id.removePrefix("sc:")
            val meta = get("https://api-v2.soundcloud.com/tracks/$numeric?client_id=$cid")
                ?: error("no track meta")
            val o = JSONObject(meta)

            // Legacy stream_url
            o.optString("stream_url").takeIf { it.startsWith("http") }?.let { su ->
                val resolved = resolveMediaUrl("$su?client_id=$cid")
                if (resolved != null) {
                    return@withContext StreamInfo(
                        url = resolved,
                        mimeType = "audio/mpeg",
                        qualityLabel = "sq",
                        expiresAtEpochMs = System.currentTimeMillis() + 10 * 60_000L,
                    )
                }
            }

            val media = o.optJSONObject("media") ?: error("no media")
            val transcodings = media.optJSONArray("transcodings") ?: error("no transcodings")

            var progressive: JSONObject? = null
            var hlsMpeg: JSONObject? = null
            var hlsAny: JSONObject? = null
            for (i in 0 until transcodings.length()) {
                val t = transcodings.getJSONObject(i)
                val protocol = t.optJSONObject("format")?.optString("protocol").orEmpty()
                val mime = t.optJSONObject("format")?.optString("mime_type").orEmpty()
                when {
                    protocol == "progressive" -> progressive = t
                    protocol == "hls" && mime.contains("mpeg") && !mime.contains("mpegurl") ->
                        hlsMpeg = hlsMpeg ?: t
                    protocol == "hls" -> hlsAny = hlsAny ?: t
                }
            }
            val chosen = progressive ?: hlsMpeg ?: hlsAny
                ?: error("no usable transcoding")

            val streamApi = chosen.optString("url")
            if (streamApi.isBlank()) error("empty transcoding url")
            val sep = if (streamApi.contains("?")) "&" else "?"
            val resolvedJson = get("$streamApi${sep}client_id=$cid")
                ?: error("resolve failed")
            val finalUrl = JSONObject(resolvedJson).optString("url")
            if (finalUrl.isBlank()) error("empty final url")

            val mime = chosen.optJSONObject("format")?.optString("mime_type")
            StreamInfo(
                url = finalUrl,
                mimeType = mime,
                qualityLabel = chosen.optString("quality").ifBlank {
                    if (progressive != null) "progressive" else "hls"
                },
                expiresAtEpochMs = System.currentTimeMillis() + 15 * 60_000L,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to "https://soundcloud.com/",
                    "Origin" to "https://soundcloud.com",
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "stream failed for ${track.id}: ${t.message}", t)
            null
        }
    }

    private fun parseTrack(o: JSONObject): Track? {
        val id = o.optLong("id").takeIf { it > 0 }?.toString() ?: return null
        val title = o.optString("title").ifBlank { return null }
        val user = o.optJSONObject("user")
        val artistName = user?.optString("username").orEmpty().ifBlank { "SoundCloud" }
        val art = o.optString("artwork_url")
            .ifBlank { user?.optString("avatar_url").orEmpty() }
            .replace("-large", "-t500x500")
        val duration = o.optLong("duration")
        return Track(
            id = "sc:$id",
            title = title,
            artists = listOf(
                Artist(
                    id = "sc-user-${user?.optLong("id")}",
                    name = artistName,
                    source = MusicSource.SOUNDCLOUD,
                    imageUrl = user?.optString("avatar_url"),
                ),
            ),
            durationMs = duration,
            source = MusicSource.SOUNDCLOUD,
            coverUrl = art.ifBlank { null },
            sourceUri = o.optString("permalink_url").ifBlank { null },
        )
    }

    private fun requireClientId(): String {
        clientId.get()?.let { return it }
        synchronized(this) {
            clientId.get()?.let { return it }
            val discovered = discoverClientId()
                ?: error("SoundCloud client_id discovery failed")
            clientId.set(discovered)
            Log.i(TAG, "client_id discovered: ${discovered.take(6)}…")
            return discovered
        }
    }

    /**
     * Live site embeds: client_id:"emAJdGEj1mm9yjoCD2jkixmgqrGIyfpi" in a-v2 bundles.
     */
    private fun discoverClientId(): String? {
        val pages = listOf(
            "https://soundcloud.com",
            "https://soundcloud.com/discover",
        )
        val scriptUrls = linkedSetOf<String>()
        for (page in pages) {
            val html = get(page) ?: continue
            // Match any a-v2 asset js URL (quoted or not)
            Regex("""https://a-v2\.sndcdn\.com/assets/[^"'\\\s]+\.js""")
                .findAll(html)
                .forEach { scriptUrls += it.value }
            // Also widget bundles (sometimes hold fallback ids)
            Regex("""https://widget\.sndcdn\.com/[^"'\\\s]+\.js""")
                .findAll(html)
                .forEach { scriptUrls += it.value }
        }
        // widget player page
        get("https://w.soundcloud.com/player/?url=https%3A//api.soundcloud.com/tracks/293")?.let { html ->
            Regex("""https://widget\.sndcdn\.com/[^"'\\\s]+\.js""")
                .findAll(html)
                .forEach { scriptUrls += it.value }
        }

        Log.d(TAG, "discovery scripts=${scriptUrls.size}")
        val patterns = listOf(
            Regex("""client_id\s*:\s*"([a-zA-Z0-9]{32})""""),
            Regex("""client_id\s*:\s*'([a-zA-Z0-9]{32})'"""),
            Regex("""client_id["']?\s*:\s*["']([a-zA-Z0-9]{32})["']"""),
            Regex("""client_id=([a-zA-Z0-9]{32})"""),
            Regex("""["']client_id["']\s*:\s*["']([a-zA-Z0-9]{32})["']"""),
            // widget ternary form: client_id:a?"xxx":"yyy"
            Regex("""client_id\s*:\s*[^?]*\?\s*"([a-zA-Z0-9]{32})"\s*:\s*"([a-zA-Z0-9]{32})""""),
        )

        for (script in scriptUrls.reversed()) {
            val js = get(script) ?: continue
            for (p in patterns) {
                val m = p.find(js) ?: continue
                val id = m.groupValues.getOrNull(1)?.takeIf { it.length >= 32 }
                    ?: continue
                if (id.all { it.isLetterOrDigit() }) {
                    // validate quickly
                    if (validateClientId(id)) return id
                    Log.w(TAG, "client_id rejected by API: ${id.take(6)}")
                }
                // ternary: try both
                if (m.groupValues.size >= 3) {
                    m.groupValues[2].takeIf { it.length == 32 && validateClientId(it) }?.let {
                        return it
                    }
                }
            }
        }
        return null
    }

    private fun validateClientId(cid: String): Boolean {
        return runCatching {
            val url =
                "https://api-v2.soundcloud.com/search/tracks?q=a&client_id=$cid&limit=1"
            val body = get(url)
            body != null && body.contains("collection")
        }.getOrDefault(false)
    }

    private fun resolveMediaUrl(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://soundcloud.com/")
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null
            else {
                // may be redirect to mp3 or JSON
                val body = resp.body?.string().orEmpty()
                if (body.startsWith("{")) {
                    JSONObject(body).optString("url").takeIf { it.startsWith("http") }
                } else {
                    resp.request.url.toString()
                }
            }
        }
    }

    private fun get(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/javascript, text/css, */*; q=0.01")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", "https://soundcloud.com")
            .header("Referer", "https://soundcloud.com/")
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} $url")
                null
            } else {
                resp.body?.string()
            }
        }
    }

    private fun String.encode(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

    companion object {
        private const val TAG = "SoundCloudSource"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
