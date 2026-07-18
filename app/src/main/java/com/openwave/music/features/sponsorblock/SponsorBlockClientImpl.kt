package com.openwave.music.features.sponsorblock

import com.openwave.music.core.domain.SkipSegment
import com.openwave.music.features.SponsorBlockClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SponsorBlock](https://sponsor.ajay.app/) public API.
 * Call from player when videoId known; seek over segments in category filter.
 */
@Singleton
class SponsorBlockClientImpl @Inject constructor(
    private val http: OkHttpClient,
) : SponsorBlockClient {

    override suspend fun segments(videoId: String): List<SkipSegment> = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext emptyList()
        val url =
            "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=[\"sponsor\",\"intro\",\"outro\",\"selfpromo\",\"music_offtopic\"]"
        runCatching {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string().orEmpty()
                if (body.isBlank() || body == "Not Found") return@use emptyList()
                parse(body)
            }
        }.getOrDefault(emptyList())
    }

    private fun parse(json: String): List<SkipSegment> {
        val arr = JSONArray(json)
        val out = ArrayList<SkipSegment>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val seg = o.getJSONArray("segment")
            val start = (seg.getDouble(0) * 1000).toLong()
            val end = (seg.getDouble(1) * 1000).toLong()
            out += SkipSegment(
                category = o.optString("category", "sponsor"),
                startMs = start,
                endMs = end,
            )
        }
        return out
    }
}
