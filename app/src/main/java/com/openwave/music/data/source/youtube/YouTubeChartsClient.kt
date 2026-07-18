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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube Charts (https://charts.youtube.com) via InnerTube WEB_MUSIC_ANALYTICS.
 *
 * POST https://charts.youtube.com/youtubei/v1/browse
 * query is URL-encoded params (not JSON):
 *   perspective=CHART_HOME&chart_params_country_code=us
 *
 * US "CHART_HOME" returns **daily** top tracks; artists may be weekly —
 * when so we derive daily artists from the song chart.
 */
@Singleton
class YouTubeChartsClient @Inject constructor(
    private val http: OkHttpClient,
) {
    data class ChartSong(
        val rank: Int,
        val track: Track,
        val viewCount: String?,
    )

    data class ChartArtist(
        val rank: Int,
        val artist: Artist,
        val channelId: String?,
        val viewCount: String?,
        val coverUrl: String?,
    )

    data class DailyCharts(
        val region: String,
        val songs: List<ChartSong>,
        val artists: List<ChartArtist>,
        /** Chart end date when known (yyyy-MM-dd). */
        val songsEndDate: String?,
        val artistsEndDate: String?,
        val songsPeriod: String?,
        val artistsPeriod: String?,
    )

    suspend fun dailyCharts(
        preferredRegion: String = "us",
        limit: Int = 30,
    ): DailyCharts = withContext(Dispatchers.IO) {
        val regions = listOf(
            preferredRegion.lowercase(Locale.US),
            "us",
            "global",
        ).distinct()

        var lastError: Throwable? = null
        for (region in regions) {
            runCatching { fetchHome(region, limit) }
                .onSuccess { if (it.songs.isNotEmpty() || it.artists.isNotEmpty()) return@withContext it }
                .onFailure {
                    lastError = it
                    Log.w(TAG, "charts $region: ${it.message}")
                }
        }
        throw lastError ?: IllegalStateException("YouTube Charts empty")
    }

    private fun fetchHome(region: String, limit: Int): DailyCharts {
        val query = "perspective=CHART_HOME&chart_params_country_code=$region"
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", CLIENT_NAME)
                        .put("clientVersion", CLIENT_VERSION)
                        .put("hl", "en")
                        .put("gl", region.uppercase(Locale.US).take(2).ifBlank { "US" })
                        .put("theme", "MUSIC"),
                ),
            )
            .put("browseId", BROWSE_HOME)
            .put("query", query)
            .toString()

        val req = Request.Builder()
            .url(BROWSE_URL)
            .post(body.toRequestBody(JSON))
            .header("User-Agent", UA)
            .header("Content-Type", "application/json")
            .header("Origin", "https://charts.youtube.com")
            .header("Referer", "https://charts.youtube.com/charts/TopSongs/$region/daily")
            .header("X-YouTube-Client-Name", CLIENT_NAME_ID)
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .build()

        val raw = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        if (raw.isBlank()) error("empty body")
        return parseHome(raw, region, limit)
    }

    private fun parseHome(raw: String, region: String, limit: Int): DailyCharts {
        val root = JSONObject(raw)
        val content = root
            .optJSONObject("contents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
            ?.optJSONObject(0)
            ?.optJSONObject("musicAnalyticsSectionRenderer")
            ?.optJSONObject("content")
            ?: error("no chart content")

        val trackTypes = content.optJSONArray("trackTypes") ?: JSONArray()
        val trackBlock = pickPeriodBlock(trackTypes, preferDaily = true)
        val songs = parseTracks(trackBlock?.optJSONArray("trackViews"), limit)
        val songsEnd = trackBlock?.optString("endDate")?.takeIf { it.isNotBlank() }
        val songsPeriod = trackBlock?.optString("chartPeriodType")?.takeIf { it.isNotBlank() }

        val artistTypes = content.optJSONArray("artists") ?: JSONArray()
        val artistBlock = pickPeriodBlock(artistTypes, preferDaily = true)
        var artists = parseArtists(artistBlock?.optJSONArray("artistViews"), limit)
        var artistsEnd = artistBlock?.optString("endDate")?.takeIf { it.isNotBlank() }
        var artistsPeriod = artistBlock?.optString("chartPeriodType")?.takeIf { it.isNotBlank() }

        // Prefer daily artists derived from daily song chart when API only has weekly artists.
        val songsAreDaily = songsPeriod?.contains("DAILY", ignoreCase = true) == true
        val artistsAreDaily = artistsPeriod?.contains("DAILY", ignoreCase = true) == true
        if (songsAreDaily && !artistsAreDaily && songs.isNotEmpty()) {
            artists = deriveArtistsFromSongs(songs, limit)
            artistsEnd = songsEnd
            artistsPeriod = songsPeriod
        }

        Log.i(
            TAG,
            "region=$region songs=${songs.size}($songsPeriod $songsEnd) " +
                "artists=${artists.size}($artistsPeriod $artistsEnd)",
        )
        return DailyCharts(
            region = region,
            songs = songs,
            artists = artists,
            songsEndDate = songsEnd,
            artistsEndDate = artistsEnd,
            songsPeriod = songsPeriod,
            artistsPeriod = artistsPeriod,
        )
    }

    /** Prefer DAILY TOP_VIEWS_CHART block when present. */
    private fun pickPeriodBlock(arr: JSONArray, preferDaily: Boolean): JSONObject? {
        var daily: JSONObject? = null
        var weekly: JSONObject? = null
        var first: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (first == null) first = o
            val listType = o.optString("listType")
            if (listType.isNotBlank() && listType != "TOP_VIEWS_CHART") continue
            val period = o.optString("chartPeriodType")
            when {
                period.contains("DAILY", ignoreCase = true) -> daily = o
                period.contains("WEEKLY", ignoreCase = true) -> weekly = weekly ?: o
            }
        }
        return when {
            preferDaily && daily != null -> daily
            weekly != null -> weekly
            else -> first
        }
    }

    private fun parseTracks(arr: JSONArray?, limit: Int): List<ChartSong> {
        if (arr == null) return emptyList()
        val out = ArrayList<ChartSong>(minOf(limit, arr.length()))
        for (i in 0 until arr.length()) {
            if (out.size >= limit) break
            val o = arr.optJSONObject(i) ?: continue
            if (!o.optBoolean("isVisible", true)) continue
            val videoId = o.optString("encryptedVideoId").takeIf { it.isNotBlank() } ?: continue
            val title = o.optString("name").takeIf { it.isNotBlank() } ?: continue
            val artistsArr = o.optJSONArray("artists") ?: JSONArray()
            val artists = ArrayList<Artist>()
            for (j in 0 until artistsArr.length()) {
                val a = artistsArr.optJSONObject(j) ?: continue
                val name = a.optString("name").takeIf { it.isNotBlank() } ?: continue
                artists += Artist(
                    id = a.optString("kgMid").ifBlank { "yt-$name" },
                    name = name,
                    source = MusicSource.YOUTUBE_MUSIC,
                )
            }
            if (artists.isEmpty()) {
                artists += Artist(id = "yt-unknown", name = "YouTube", source = MusicSource.YOUTUBE_MUSIC)
            }
            val thumbs = o.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val cover = bestThumb(thumbs)
            val rank = o.optJSONObject("chartEntryMetadata")?.optInt("currentPosition")
                ?.takeIf { it > 0 } ?: (out.size + 1)
            val track = Track(
                id = videoId,
                title = title,
                artists = artists,
                source = MusicSource.YOUTUBE_MUSIC,
                coverUrl = cover,
                sourceUri = "https://music.youtube.com/watch?v=$videoId",
            )
            out += ChartSong(
                rank = rank,
                track = track,
                viewCount = o.optString("viewCount").takeIf { it.isNotBlank() },
            )
        }
        return out.sortedBy { it.rank }
    }

    private fun parseArtists(arr: JSONArray?, limit: Int): List<ChartArtist> {
        if (arr == null) return emptyList()
        val out = ArrayList<ChartArtist>(minOf(limit, arr.length()))
        for (i in 0 until arr.length()) {
            if (out.size >= limit) break
            val o = arr.optJSONObject(i) ?: continue
            if (!o.optBoolean("isVisible", true)) continue
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
            val channelId = o.optString("externalChannelId").takeIf { it.isNotBlank() }
            val id = channelId
                ?: o.optString("id").takeIf { it.isNotBlank() }
                ?: "yt-artist-$name"
            val thumbs = o.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val cover = bestThumb(thumbs)
            val rank = o.optJSONObject("chartEntryMetadata")?.optInt("currentPosition")
                ?.takeIf { it > 0 } ?: (out.size + 1)
            out += ChartArtist(
                rank = rank,
                artist = Artist(
                    id = id,
                    name = name,
                    source = MusicSource.YOUTUBE_MUSIC,
                    imageUrl = cover,
                ),
                channelId = channelId,
                viewCount = o.optString("viewCount").takeIf { it.isNotBlank() },
                coverUrl = cover,
            )
        }
        return out.sortedBy { it.rank }
    }

    /** Rank artists by best chart position among daily songs. */
    private fun deriveArtistsFromSongs(songs: List<ChartSong>, limit: Int): List<ChartArtist> {
        data class Agg(var bestRank: Int, var cover: String?, var views: Long)
        val map = linkedMapOf<String, Pair<Artist, Agg>>()
        for (s in songs) {
            val a = s.track.artists.firstOrNull() ?: continue
            val key = a.name.lowercase(Locale.US)
            val views = s.viewCount?.toLongOrNull() ?: 0L
            val cur = map[key]
            if (cur == null) {
                map[key] = a to Agg(s.rank, s.track.coverUrl, views)
            } else {
                val agg = cur.second
                if (s.rank < agg.bestRank) {
                    agg.bestRank = s.rank
                    if (s.track.coverUrl != null) agg.cover = s.track.coverUrl
                }
                agg.views += views
            }
        }
        return map.values
            .sortedWith(compareBy({ it.second.bestRank }, { -it.second.views }))
            .take(limit)
            .mapIndexed { idx, (artist, agg) ->
                ChartArtist(
                    rank = idx + 1,
                    artist = artist.copy(imageUrl = artist.imageUrl ?: agg.cover),
                    channelId = null,
                    viewCount = agg.views.takeIf { it > 0 }?.toString(),
                    coverUrl = artist.imageUrl ?: agg.cover,
                )
            }
    }

    private fun bestThumb(thumbs: JSONArray?): String? {
        if (thumbs == null || thumbs.length() == 0) return null
        // Prefer mid/high res; last is often maxres
        var best: String? = null
        var bestW = -1
        for (i in 0 until thumbs.length()) {
            val t = thumbs.optJSONObject(i) ?: continue
            val url = t.optString("url").takeIf { it.isNotBlank() } ?: continue
            val w = t.optInt("width", 0)
            if (w >= bestW) {
                bestW = w
                best = url
            } else if (best == null) {
                best = url
            }
        }
        return best?.replace("http://", "https://")
    }

    companion object {
        private const val TAG = "YtCharts"
        private const val CLIENT_NAME = "WEB_MUSIC_ANALYTICS"
        private const val CLIENT_VERSION = "2.0"
        private const val CLIENT_NAME_ID = "31"
        private const val BROWSE_HOME = "FEmusic_analytics_charts_home"
        private const val BROWSE_URL =
            "https://charts.youtube.com/youtubei/v1/browse?alt=json"
        private val JSON = "application/json".toMediaType()
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun formatChartDate(iso: String?): String? {
            if (iso.isNullOrBlank()) return null
            return runCatching {
                val d = LocalDate.parse(iso)
                d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
            }.getOrElse { iso }
        }
    }
}
