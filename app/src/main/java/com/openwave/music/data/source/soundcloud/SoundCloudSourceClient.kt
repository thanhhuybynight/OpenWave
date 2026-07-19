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
 * SoundCloud API v2 (anonymous).
 *
 * client_id is discovered from the public web client (mobile HTML embeds
 * `clientId`, desktop a-v2 bundles embed `client_id`). On 401 we rediscover.
 */
@Singleton
class SoundCloudSourceClient @Inject constructor(
    baseHttp: OkHttpClient,
) : MusicSourceClient {

    override val source: MusicSource = MusicSource.SOUNDCLOUD
    override val supportsAnonymousPlayback: Boolean = true

    private val http: OkHttpClient = baseHttp.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val clientId = AtomicReference<String?>(null)

    override suspend fun search(query: String, limit: Int): SearchResult =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext SearchResult()
            // Propagate failures so FastMusicCatalog marks SoundCloud with an error
            // instead of a silent empty "✓".
            val body = apiGet(
                "https://api-v2.soundcloud.com/search/tracks" +
                    "?q=${q.encode()}" +
                    "&limit=$limit&offset=0&linked_partitioning=1",
            ) ?: error("SoundCloud search HTTP failed")
            val arr = JSONObject(body).optJSONArray("collection") ?: JSONArray()
            val tracks = buildList {
                for (i in 0 until arr.length()) {
                    parseTrack(arr.optJSONObject(i) ?: continue)?.let { add(it) }
                }
            }
            if (tracks.isEmpty()) {
                Log.w(TAG, "search empty for q=$q (collection=${arr.length()})")
            } else {
                Log.d(TAG, "search '$q' -> ${tracks.size} tracks")
            }
            SearchResult(tracks = tracks)
        }

    override suspend fun getTrack(id: String): Track? = withContext(Dispatchers.IO) {
        try {
            val numeric = normalizeTrackId(id) ?: return@withContext null
            val body = apiGet("https://api-v2.soundcloud.com/tracks/$numeric")
                ?: return@withContext null
            parseTrack(JSONObject(body))
        } catch (t: Throwable) {
            Log.e(TAG, "getTrack: ${t.message}")
            null
        }
    }

    /**
     * Related / station-style tracks for a seed (SoundCloud radio).
     */
    suspend fun relatedTracks(trackId: String, limit: Int = 24): List<Track> =
        withContext(Dispatchers.IO) {
            try {
                val numeric = normalizeTrackId(trackId) ?: return@withContext emptyList()
                val body = apiGet(
                    "https://api-v2.soundcloud.com/tracks/$numeric/related" +
                        "?limit=$limit&offset=0",
                ) ?: return@withContext emptyList()
                val root = JSONObject(body)
                val arr = root.optJSONArray("collection")
                    ?: root.optJSONArray("tracks")
                    ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        parseTrack(arr.optJSONObject(i) ?: continue)?.let { add(it) }
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
        try {
            val numeric = normalizeTrackId(track.id) ?: error("bad track id ${track.id}")
            val meta = apiGet("https://api-v2.soundcloud.com/tracks/$numeric")
                ?: error("no track meta")
            val o = JSONObject(meta)

            o.optString("stream_url").takeIf { it.startsWith("http") }?.let { su ->
                val cid = requireClientId()
                val sep = if (su.contains("?")) "&" else "?"
                val resolved = resolveMediaUrl("$su${sep}client_id=$cid")
                if (resolved != null) {
                    return@withContext StreamInfo(
                        url = resolved,
                        mimeType = "audio/mpeg",
                        qualityLabel = "sq",
                        expiresAtEpochMs = System.currentTimeMillis() + 10 * 60_000L,
                        headers = streamHeaders(),
                    )
                }
            }

            val media = o.optJSONObject("media") ?: error("no media")
            val transcodings = media.optJSONArray("transcodings") ?: error("no transcodings")

            var progressive: JSONObject? = null
            var hlsMpeg: JSONObject? = null
            var hlsAny: JSONObject? = null
            for (i in 0 until transcodings.length()) {
                val t = transcodings.optJSONObject(i) ?: continue
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
            val cid = requireClientId()
            val sep = if (streamApi.contains("?")) "&" else "?"
            val resolvedJson = apiGetRaw("$streamApi${sep}client_id=$cid")
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
                headers = streamHeaders(),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "stream failed for ${track.id}: ${t.message}", t)
            null
        }
    }

    private fun streamHeaders(): Map<String, String> = mapOf(
        "User-Agent" to DESKTOP_UA,
        "Referer" to "https://soundcloud.com/",
        "Origin" to "https://soundcloud.com",
    )

    /**
     * Uploader username becomes the sole artist credit (used in subtitles).
     */
    private fun parseTrack(o: JSONObject?): Track? {
        if (o == null) return null
        val id = extractId(o) ?: return null
        val title = o.optString("title").trim().ifBlank { return null }
        val user = o.optJSONObject("user")
        val uploader = user?.optString("username")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: user?.optString("full_name")?.trim()?.takeIf { it.isNotBlank() }
            ?: "SoundCloud"
        val userId = extractUserId(user)
        val art = o.optString("artwork_url")
            .ifBlank { user?.optString("avatar_url").orEmpty() }
            .replace("-large", "-t500x500")
            .takeIf { it.startsWith("http") }
        val duration = o.optLong("duration").takeIf { it > 0 }
            ?: o.optLong("full_duration").takeIf { it > 0 }
            ?: 0L
        return Track(
            id = "sc:$id",
            title = title,
            artists = listOf(
                Artist(
                    id = "sc-user-${userId ?: uploader.hashCode()}",
                    name = uploader,
                    source = MusicSource.SOUNDCLOUD,
                    imageUrl = user?.optString("avatar_url")?.takeIf { it.startsWith("http") },
                ),
            ),
            durationMs = duration,
            source = MusicSource.SOUNDCLOUD,
            coverUrl = art,
            sourceUri = o.optString("permalink_url").takeIf { it.startsWith("http") },
        )
    }

    /** Prefer numeric id; fall back to URN tail; accept string ids. */
    private fun extractId(o: JSONObject): String? {
        if (o.has("id") && !o.isNull("id")) {
            val raw = o.opt("id")
            when (raw) {
                is Number -> {
                    val n = raw.toLong()
                    if (n > 0L) return n.toString()
                }
                is String -> {
                    val s = raw.trim()
                    if (s.isNotEmpty() && s != "null") {
                        return s.removePrefix("soundcloud:tracks:").takeIf { it.isNotBlank() }
                    }
                }
            }
            // optLong returns 0 on failure — only trust positive
            val asLong = o.optLong("id", -1L)
            if (asLong > 0L) return asLong.toString()
            o.optString("id").trim()
                .removePrefix("soundcloud:tracks:")
                .takeIf { it.isNotBlank() && it != "null" && it != "0" }
                ?.let { return it }
        }
        val urn = o.optString("urn").trim()
        if (urn.contains(":")) {
            return urn.substringAfterLast(':').takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun extractUserId(user: JSONObject?): String? {
        if (user == null) return null
        if (user.has("id") && !user.isNull("id")) {
            val raw = user.opt("id")
            when (raw) {
                is Number -> return raw.toLong().toString()
                is String -> return raw.trim().takeIf { it.isNotBlank() }
            }
            val n = user.optLong("id", -1L)
            if (n > 0L) return n.toString()
        }
        return user.optString("urn").substringAfterLast(':').takeIf { it.isNotBlank() }
    }

    private fun normalizeTrackId(id: String): String? {
        val raw = id.removePrefix("sc:").trim()
        if (raw.isBlank()) return null
        return raw.removePrefix("soundcloud:tracks:").takeIf { it.isNotBlank() }
    }

    // ── HTTP + client_id ────────────────────────────────────────────────────

    /**
     * Authenticated API GET with automatic client_id and one rediscover on 401.
     */
    private fun apiGet(pathOrUrl: String): String? {
        val base = if (pathOrUrl.startsWith("http")) pathOrUrl else "https://api-v2.soundcloud.com$pathOrUrl"
        return apiGetRaw(withClientId(base))
    }

    private fun apiGetRaw(url: String): String? {
        val first = get(url)
        if (first.status == 401 || first.status == 403) {
            Log.w(TAG, "auth ${first.status} — rediscovering client_id")
            invalidateClientId()
            val retryUrl = withClientId(stripClientId(url))
            val second = get(retryUrl)
            if (!second.isSuccessful) {
                Log.w(TAG, "HTTP ${second.status} after rediscover $retryUrl")
                return null
            }
            return second.body
        }
        if (!first.isSuccessful) {
            Log.w(TAG, "HTTP ${first.status} $url")
            return null
        }
        return first.body
    }

    private fun withClientId(url: String): String {
        val cid = requireClientId()
        val sep = if (url.contains("?")) "&" else "?"
        return if (url.contains("client_id=")) {
            url.replace(Regex("""client_id=[^&]*"""), "client_id=$cid")
        } else {
            "$url${sep}client_id=$cid"
        }
    }

    private fun stripClientId(url: String): String =
        url.replace(Regex("""([?&])client_id=[^&]*"""), "$1")
            .replace("?&", "?")
            .removeSuffix("?")
            .removeSuffix("&")

    private fun requireClientId(): String {
        clientId.get()?.let { return it }
        synchronized(this) {
            clientId.get()?.let { return it }
            val discovered = discoverClientId()
                ?: error("SoundCloud client_id discovery failed")
            clientId.set(discovered)
            Log.i(TAG, "client_id ready: ${discovered.take(6)}…")
            return discovered
        }
    }

    private fun invalidateClientId() {
        clientId.set(null)
    }

    /**
     * Fast paths first:
     * 1) Mobile site HTML embeds `"clientId":"…"` (no JS download)
     * 2) Desktop a-v2 asset bundles
     * 3) Known seed ids (validated live)
     */
    private fun discoverClientId(): String? {
        // 1) Mobile HTML — instant, no extra JS
        runCatching {
            val html = getHtml("https://soundcloud.com/", MOBILE_UA)
                ?: getHtml("https://m.soundcloud.com/", MOBILE_UA)
            if (html != null) {
                extractClientIdsFromText(html).forEach { cid ->
                    if (validateClientId(cid)) {
                        Log.i(TAG, "client_id from mobile HTML")
                        return cid
                    }
                }
            }
        }.onFailure { Log.w(TAG, "mobile HTML discovery: ${it.message}") }

        // 2) Desktop homepage + a-v2 scripts
        runCatching {
            val html = getHtml("https://soundcloud.com/", DESKTOP_UA) ?: return@runCatching
            val scripts = linkedSetOf<String>()
            SCRIPT_URL_RE.findAll(html).forEach { scripts += it.value }
            // Prefer smaller numbered chunks that historically hold client_id
            val ordered = scripts.sortedBy { url ->
                when {
                    url.contains("/55-") || url.contains("/47-") || url.contains("/48-") -> 0
                    url.contains("a-v2.sndcdn.com") -> 1
                    else -> 2
                }
            }
            Log.d(TAG, "desktop scripts=${ordered.size}")
            for (script in ordered) {
                val js = getHtml(script, DESKTOP_UA) ?: continue
                extractClientIdsFromText(js).forEach { cid ->
                    if (validateClientId(cid)) {
                        Log.i(TAG, "client_id from $script")
                        return cid
                    }
                }
            }
        }.onFailure { Log.w(TAG, "desktop discovery: ${it.message}") }

        // 3) Widget player scripts
        runCatching {
            val widgetHtml = getHtml(
                "https://w.soundcloud.com/player/?url=https%3A//api.soundcloud.com/tracks/293",
                DESKTOP_UA,
            )
            if (widgetHtml != null) {
                val scripts = linkedSetOf<String>()
                SCRIPT_URL_RE.findAll(widgetHtml).forEach { scripts += it.value }
                WIDGET_JS_RE.findAll(widgetHtml).forEach { scripts += it.value }
                for (script in scripts) {
                    val js = getHtml(script, DESKTOP_UA) ?: continue
                    extractClientIdsFromText(js).forEach { cid ->
                        if (validateClientId(cid)) {
                            Log.i(TAG, "client_id from widget")
                            return cid
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "widget discovery: ${it.message}") }

        // 4) Seed fallbacks (public web client ids; validated before use)
        for (seed in SEED_CLIENT_IDS) {
            if (validateClientId(seed)) {
                Log.i(TAG, "client_id from seed ${seed.take(6)}…")
                return seed
            }
        }
        return null
    }

    private fun extractClientIdsFromText(text: String): List<String> {
        val out = linkedSetOf<String>()
        for (p in CLIENT_ID_PATTERNS) {
            p.findAll(text).forEach { m ->
                for (i in 1 until m.groupValues.size) {
                    val id = m.groupValues[i]
                    if (id.length in 16..40 && id.all { it.isLetterOrDigit() }) {
                        out += id
                    }
                }
            }
        }
        return out.toList()
    }

    private fun validateClientId(cid: String): Boolean {
        return runCatching {
            val url =
                "https://api-v2.soundcloud.com/search/tracks?q=a&client_id=$cid&limit=1"
            val resp = get(url)
            resp.isSuccessful && (resp.body?.contains("\"collection\"") == true)
        }.getOrDefault(false)
    }

    private fun resolveMediaUrl(url: String): String? {
        val resp = get(url)
        if (!resp.isSuccessful) return null
        val body = resp.body.orEmpty()
        return if (body.startsWith("{")) {
            JSONObject(body).optString("url").takeIf { it.startsWith("http") }
        } else {
            // may already be the media URL after redirects
            resp.finalUrl?.takeIf { it.startsWith("http") }
        }
    }

    private data class HttpResult(
        val status: Int,
        val body: String?,
        val finalUrl: String? = null,
    ) {
        val isSuccessful: Boolean get() = status in 200..299
    }

    private fun get(url: String): HttpResult {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "application/json, text/javascript, */*;q=0.1")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", "https://soundcloud.com")
            .header("Referer", "https://soundcloud.com/")
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            HttpResult(
                status = resp.code,
                body = resp.body?.string(),
                finalUrl = resp.request.url.toString(),
            )
        }
    }

    private fun getHtml(url: String, userAgent: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/javascript,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://soundcloud.com/")
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTML ${resp.code} $url")
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
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val SCRIPT_URL_RE =
            Regex("""https://a-v2\.sndcdn\.com/assets/[^"'\\\s]+\.js""")
        private val WIDGET_JS_RE =
            Regex("""https://widget\.sndcdn\.com/[^"'\\\s]+\.js""")

        private val CLIENT_ID_PATTERNS = listOf(
            // Mobile Next.js: "clientId":"KKzJ…"
            Regex("""["']clientId["']\s*:\s*["']([a-zA-Z0-9]{16,40})["']"""),
            Regex("""client_id\s*:\s*"([a-zA-Z0-9]{16,40})""""),
            Regex("""client_id\s*:\s*'([a-zA-Z0-9]{16,40})'"""),
            Regex("""["']client_id["']\s*:\s*["']([a-zA-Z0-9]{16,40})["']"""),
            Regex("""client_id=([a-zA-Z0-9]{16,40})"""),
            // ternary form in widget bundles
            Regex(
                """client_id\s*:\s*[^?]*\?\s*"([a-zA-Z0-9]{16,40})"\s*:\s*"([a-zA-Z0-9]{16,40})"""",
            ),
        )

        /**
         * Public web client ids observed in SoundCloud frontends.
         * Always validated before use; rotated when Google/SC revoke them.
         */
        private val SEED_CLIENT_IDS = listOf(
            "KKzJxmw11tYpCs6T24P4uUYhqmjalG6M",
            "emAJdGEj1mm9yjoCD2jkixmgqrGIyfpi",
        )
    }
}
