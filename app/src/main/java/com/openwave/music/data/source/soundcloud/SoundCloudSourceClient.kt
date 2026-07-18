package com.openwave.music.data.source.soundcloud

import android.util.Log
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.MusicSourceClient
import com.openwave.music.core.domain.SearchResult
import com.openwave.music.core.domain.StreamInfo
import com.openwave.music.core.domain.Track
import com.openwave.music.data.source.DemoCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SoundCloud public API v2 with [client_id] discovery from web assets.
 */
@Singleton
class SoundCloudSourceClient @Inject constructor(
    private val http: OkHttpClient,
) : MusicSourceClient {

    override val source: MusicSource = MusicSource.SOUNDCLOUD
    override val supportsAnonymousPlayback: Boolean = true

    private val clientId = AtomicReference<String?>(null)

    override suspend fun search(query: String, limit: Int): SearchResult =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext SearchResult()
            runCatching {
                val cid = ensureClientId() ?: error("no client_id")
                val url =
                    "https://api-v2.soundcloud.com/search/tracks?q=${q.encode()}" +
                        "&client_id=$cid&limit=$limit&offset=0&app_locale=en"
                val body = get(url) ?: error("empty search body")
                val root = JSONObject(body)
                val arr = root.optJSONArray("collection") ?: JSONArray()
                val tracks = buildList {
                    for (i in 0 until arr.length()) {
                        parseTrack(arr.getJSONObject(i))?.let { add(it) }
                    }
                }
                SearchResult(tracks = tracks)
            }.onFailure { Log.w(TAG, "SC search: ${it.message}") }
                .getOrNull()
                ?.takeIf { it.tracks.isNotEmpty() }
                ?: SearchResult(
                    tracks = DemoCatalog.search(q, limit).tracks
                        .filter { it.source == MusicSource.SOUNDCLOUD },
                )
        }

    override suspend fun getTrack(id: String): Track? = withContext(Dispatchers.IO) {
        runCatching {
            val cid = ensureClientId() ?: return@runCatching null
            val numeric = id.removePrefix("sc:")
            val body = get("https://api-v2.soundcloud.com/tracks/$numeric?client_id=$cid")
                ?: return@runCatching null
            parseTrack(JSONObject(body))
        }.getOrNull()
            ?: DemoCatalog.tracks().firstOrNull { it.id == id }
    }

    override suspend fun getStream(track: Track): StreamInfo? = withContext(Dispatchers.IO) {
        if (!track.streamUrl.isNullOrBlank() &&
            track.streamUrl!!.startsWith("http") &&
            !track.streamUrl!!.contains("api-v2.soundcloud") &&
            !track.streamUrl!!.contains("soundcloud.com")
        ) {
            // Demo / non-SC progressive URL
            return@withContext StreamInfo(url = track.streamUrl!!, mimeType = "audio/mpeg")
        }
        runCatching {
            val cid = ensureClientId() ?: error("no client_id")
            val numeric = track.id.removePrefix("sc:")
            val meta = get("https://api-v2.soundcloud.com/tracks/$numeric?client_id=$cid")
                ?: error("no track meta")
            val o = JSONObject(meta)
            // Some tracks expose stream_url directly (legacy)
            o.optString("stream_url").takeIf { it.startsWith("http") }?.let { su ->
                val final = resolveRedirect("$su?client_id=$cid") ?: su
                return@runCatching StreamInfo(
                    url = final,
                    mimeType = "audio/mpeg",
                    qualityLabel = "sq",
                    expiresAtEpochMs = System.currentTimeMillis() + 10 * 60_000L,
                )
            }
            val media = o.optJSONObject("media") ?: error("no media")
            val transcodings = media.optJSONArray("transcodings") ?: error("no transcodings")

            var progressive: JSONObject? = null
            var hls: JSONObject? = null
            for (i in 0 until transcodings.length()) {
                val t = transcodings.getJSONObject(i)
                val protocol = t.optJSONObject("format")?.optString("protocol").orEmpty()
                when {
                    protocol == "progressive" -> progressive = t
                    protocol.contains("hls") -> hls = t
                }
            }
            val chosen = progressive ?: hls ?: error("no usable transcoding")
            val streamApi = chosen.optString("url")
            if (streamApi.isBlank()) error("empty transcoding url")
            val sep = if (streamApi.contains("?")) "&" else "?"
            val resolved = get("$streamApi${sep}client_id=$cid") ?: error("resolve failed")
            val finalUrl = JSONObject(resolved).optString("url")
            if (finalUrl.isBlank()) error("empty final url")
            StreamInfo(
                url = finalUrl,
                mimeType = chosen.optJSONObject("format")?.optString("mime_type"),
                qualityLabel = chosen.optString("quality").ifBlank { "sq" },
                expiresAtEpochMs = System.currentTimeMillis() + 15 * 60_000L,
            )
        }.onFailure { Log.w(TAG, "SC stream: ${it.message}") }
            .getOrNull()
            ?: track.streamUrl?.let { StreamInfo(url = it, mimeType = "audio/mpeg") }
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

    private fun ensureClientId(): String? {
        clientId.get()?.let { return it }
        synchronized(this) {
            clientId.get()?.let { return it }
            val discovered = discoverClientId()
            if (discovered != null) clientId.set(discovered)
            else Log.w(TAG, "client_id discovery failed")
            return discovered
        }
    }

    private fun discoverClientId(): String? {
        val home = get("https://soundcloud.com") ?: return null
        val scriptUrls = Regex("""src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"""")
            .findAll(home)
            .map { it.groupValues[1] }
            .toList()
        // Scan from the end (client_id often in later chunks)
        for (script in scriptUrls.asReversed().take(12)) {
            val js = get(script) ?: continue
            val patterns = listOf(
                Regex("""client_id["']?\s*:\s*["']([a-zA-Z0-9]{32})["']"""),
                Regex("""client_id=([a-zA-Z0-9]{32})"""),
                Regex("""["']clientId["']\s*:\s*["']([a-zA-Z0-9]{32})["']"""),
                Regex("""\bclient_id["']?\s*,\s*["']([a-zA-Z0-9]{32})["']"""),
            )
            for (p in patterns) {
                p.find(js)?.groupValues?.getOrNull(1)?.let { return it }
            }
        }
        return null
    }

    private fun resolveRedirect(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.request.url.toString()
        }
    }

    private fun get(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Origin", "https://soundcloud.com")
            .header("Referer", "https://soundcloud.com")
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} for $url")
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
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
