package com.openwave.music.data.source.soundcloud

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
 * SoundCloud public API v2 with rotated [client_id] scraped from the web app.
 * Progressive HTTP stream preferred; HLS left for Media3 HLS module.
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
            runCatching {
                val cid = ensureClientId() ?: return@runCatching null
                val url =
                    "https://api-v2.soundcloud.com/search/tracks?q=${query.encode()}&client_id=$cid&limit=$limit&offset=0"
                val body = get(url) ?: return@runCatching null
                val arr = JSONObject(body).optJSONArray("collection") ?: JSONArray()
                val tracks = buildList {
                    for (i in 0 until arr.length()) {
                        parseTrack(arr.getJSONObject(i))?.let { add(it) }
                    }
                }
                SearchResult(tracks = tracks)
            }.getOrNull()?.takeIf { it.tracks.isNotEmpty() }
                ?: SearchResult(
                    tracks = DemoCatalog.search(query, limit).tracks
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
        if (!track.streamUrl.isNullOrBlank() && track.streamUrl!!.startsWith("http")) {
            // Demo progressive URL
            if (!track.streamUrl!!.contains("soundcloud")) {
                return@withContext StreamInfo(url = track.streamUrl!!, mimeType = "audio/mpeg")
            }
        }
        runCatching {
            val cid = ensureClientId() ?: return@runCatching null
            val numeric = track.id.removePrefix("sc:")
            val meta = get("https://api-v2.soundcloud.com/tracks/$numeric?client_id=$cid")
                ?: return@runCatching null
            val o = JSONObject(meta)
            val media = o.optJSONObject("media") ?: return@runCatching null
            val transcodings = media.optJSONArray("transcodings") ?: return@runCatching null

            // Prefer progressive MPEG
            var progressive: JSONObject? = null
            var hls: JSONObject? = null
            for (i in 0 until transcodings.length()) {
                val t = transcodings.getJSONObject(i)
                val protocol = t.optJSONObject("format")?.optString("protocol").orEmpty()
                val mime = t.optJSONObject("format")?.optString("mime_type").orEmpty()
                when {
                    protocol == "progressive" -> progressive = t
                    protocol.contains("hls") -> hls = t
                    mime.contains("mpeg") && progressive == null -> progressive = t
                }
            }
            val chosen = progressive ?: hls ?: return@runCatching null
            val streamApi = chosen.optString("url")
            if (streamApi.isBlank()) return@runCatching null
            val sep = if (streamApi.contains("?")) "&" else "?"
            val resolved = get("$streamApi${sep}client_id=$cid") ?: return@runCatching null
            val finalUrl = JSONObject(resolved).optString("url")
            if (finalUrl.isBlank()) return@runCatching null
            StreamInfo(
                url = finalUrl,
                mimeType = chosen.optJSONObject("format")?.optString("mime_type"),
                qualityLabel = chosen.optString("quality").ifBlank { "sq" },
                expiresAtEpochMs = System.currentTimeMillis() + 15 * 60_000L,
            )
        }.getOrNull()
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
            return discovered
        }
    }

    /**
     * Pull client_id from SoundCloud web JS bundles (common FOSS technique).
     */
    private fun discoverClientId(): String? {
        val home = get("https://soundcloud.com") ?: return null
        val scriptUrls = Regex("""src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"""")
            .findAll(home)
            .map { it.groupValues[1] }
            .toList()
            .takeLast(8) // client_id often in later bundles
        for (script in scriptUrls.asReversed()) {
            val js = get(script) ?: continue
            val m = Regex("""client_id["']?\s*:\s*["']([a-zA-Z0-9]{32})["']""")
                .find(js)
                ?: Regex("""client_id=([a-zA-Z0-9]{32})""").find(js)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    private fun get(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) OpenWave/0.1")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }

    private fun String.encode(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}
