package com.openwave.music.features.ryd

import com.openwave.music.core.domain.VoteStats
import com.openwave.music.features.ReturnYoutubeDislikeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** [returnyoutubedislikeapi.com](https://returnyoutubedislikeapi.com/) */
@Singleton
class ReturnYoutubeDislikeClientImpl @Inject constructor(
    private val http: OkHttpClient,
) : ReturnYoutubeDislikeClient {

    override suspend fun votes(videoId: String): VoteStats? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null
        val url = "https://returnyoutubedislikeapi.com/votes?videoId=$videoId"
        runCatching {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val o = JSONObject(resp.body?.string().orEmpty())
                VoteStats(
                    videoId = videoId,
                    likes = o.optLong("likes"),
                    dislikes = o.optLong("dislikes"),
                    rating = o.optDouble("rating").takeIf { o.has("rating") },
                )
            }
        }.getOrNull()
    }
}
