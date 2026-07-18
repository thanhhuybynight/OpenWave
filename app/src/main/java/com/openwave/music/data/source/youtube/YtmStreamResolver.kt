package com.openwave.music.data.source.youtube

import android.util.Log
import com.openwave.music.core.domain.QualityPreference
import com.openwave.music.core.domain.StreamInfo
import com.openwave.music.core.domain.StreamQuality
import com.openwave.music.data.source.newpipe.NewPipeBootstrap
import com.openwave.music.data.source.newpipe.NewPipeDownloader
import com.openwave.music.features.StreamQualitySelector
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo as NpStreamInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SimpMusic-style stream resolution:
 * 1. InnerTube WEB_REMIX player (playability + optional direct URLs + cpn)
 * 2. NewPipe StreamInfo (music.youtube.com → youtube.com) for itag→URL
 * 3. Prefer progressive HTTP audio itags (140/251/…)
 *
 * Root cause of prior failures: NewPipeExtractor v0.24.5 throws
 * ContentNotAvailableException ("The page needs to be reloaded").
 */
@Singleton
class YtmStreamResolver @Inject constructor(
    private val innerTube: YtInnerTubePlayer,
    private val newPipe: NewPipeBootstrap,
    private val qualitySelector: StreamQualitySelector,
) {

    fun resolveAudio(videoId: String): StreamInfo {
        newPipe.ensureInit()
        val id = YouTubeMusicSourceClient.normalizeId(videoId)
        val errors = mutableListOf<String>()

        val player = runCatching { innerTube.player(id) }
            .onFailure {
                val msg = "innertube: ${it.message}"
                Log.w(TAG, msg)
                errors += msg
            }
            .getOrNull()

        if (player != null) {
            Log.d(TAG, "innertube status=${player.status} directUrls=${player.urlsByItag.size} ciphers=${player.cipherItags.size}")
            if (player.status != "OK" && player.status != "UNKNOWN") {
                errors += "playability=${player.status}"
            }
        }

        val itagMap = linkedMapOf<Int, String>()
        collectNewPipeItags(id, preferMusicHost = true, into = itagMap, errors = errors)
        if (!hasRequiredAudio(itagMap)) {
            collectNewPipeItags(id, preferMusicHost = false, into = itagMap, errors = errors)
        }
        player?.urlsByItag?.forEach { (itag, url) ->
            if (url.isNotBlank() && isLikelyPlayableUrl(url)) {
                itagMap.putIfAbsent(itag, url)
            }
        }

        Log.d(TAG, "itagMap size=${itagMap.size} keys=${itagMap.keys}")

        val preferred = preferredItags(qualitySelector.preference)
        val chosenItag = preferred.firstOrNull { it in itagMap }
            ?: itagMap.keys.firstOrNull { it in YtInnerTubePlayer.AUDIO_ITAG_PRIORITY }
            ?: itagMap.keys.firstOrNull()

        if (chosenItag != null) {
            val baseUrl = itagMap[chosenItag]!!
            val url = appendCpn(baseUrl, player?.cpn)
            val expiresMs = player?.expiresInSeconds
                ?.takeIf { it > 0 }
                ?.let { System.currentTimeMillis() + it * 1000L }
                ?: (System.currentTimeMillis() + 4 * 60 * 60_000L)
            Log.i(TAG, "OK $id itag=$chosenItag")
            return StreamInfo(
                url = url,
                mimeType = mimeForItag(chosenItag),
                qualityLabel = labelForItag(chosenItag),
                expiresAtEpochMs = expiresMs,
                headers = streamHeaders(),
            )
        }

        // Last resort: best progressive audio by bitrate (no itag preference)
        resolveNewPipeBestAudio(id, player?.cpn)?.let {
            Log.i(TAG, "OK $id via bitrate fallback")
            return it
        }

        val detail = errors.joinToString(" | ").ifBlank { "no audio streams" }
        Log.e(TAG, "FAILED $id: $detail")
        error("YouTube stream resolve failed for $id: $detail")
    }

    private fun collectNewPipeItags(
        videoId: String,
        preferMusicHost: Boolean,
        into: MutableMap<Int, String>,
        errors: MutableList<String>,
    ) {
        val watch = if (preferMusicHost) {
            "https://music.youtube.com/watch?v=$videoId"
        } else {
            "https://www.youtube.com/watch?v=$videoId"
        }
        runCatching {
            val info = NpStreamInfo.getInfo(ServiceList.YouTube, watch)
            val streams = info.audioStreams.orEmpty()
            Log.d(TAG, "NewPipe ${if (preferMusicHost) "music" else "www"} audio=${streams.size}")
            for (s in streams) {
                val itag = s.itag
                val content = s.content
                if (itag > 0 && !content.isNullOrBlank() && isLikelyPlayableUrl(content)) {
                    // Prefer progressive HTTP when replacing same itag
                    if (into[itag] == null || s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP) {
                        into[itag] = content
                    }
                }
            }
        }.onFailure {
            val msg = "NewPipe ${if (preferMusicHost) "music" else "www"}: ${it.javaClass.simpleName}: ${it.message}"
            Log.w(TAG, msg)
            errors += msg
        }
    }

    private fun resolveNewPipeBestAudio(videoId: String, cpn: String?): StreamInfo? {
        val urls = listOf(
            "https://www.youtube.com/watch?v=$videoId",
            "https://music.youtube.com/watch?v=$videoId",
        )
        for (u in urls) {
            val info = runCatching { NpStreamInfo.getInfo(ServiceList.YouTube, u) }
                .onFailure { Log.w(TAG, "bestAudio $u: ${it.message}") }
                .getOrNull()
                ?: continue
            val audio = pickAudioStream(info.audioStreams.orEmpty(), qualitySelector.preference)
                ?: continue
            val content = audio.content ?: continue
            if (!isLikelyPlayableUrl(content)) continue
            return StreamInfo(
                url = appendCpn(content, cpn),
                mimeType = audio.format?.mimeType ?: "audio/webm",
                qualityLabel = "${audio.averageBitrate}kbps",
                expiresAtEpochMs = System.currentTimeMillis() + 4 * 60 * 60_000L,
                headers = streamHeaders(),
            )
        }
        return null
    }

    private fun pickAudioStream(
        candidates: List<AudioStream>,
        pref: QualityPreference,
    ): AudioStream? {
        val usable = candidates.filter {
            !it.content.isNullOrBlank() && isLikelyPlayableUrl(it.content)
        }
        if (usable.isEmpty()) return null
        val ranked = usable.sortedByDescending { it.averageBitrate }
        val target = when (pref.preferred) {
            StreamQuality.MAX -> if (pref.hasYtmPremiumSession) 256 else 160
            StreamQuality.HIGH -> 128
            StreamQuality.AUTO -> 128
        }
        return ranked.firstOrNull { it.averageBitrate in (target - 40)..(target + 100) }
            ?: ranked.firstOrNull { it.averageBitrate >= 96 }
            ?: ranked.first()
    }

    private fun preferredItags(pref: QualityPreference): List<Int> = when (pref.preferred) {
        StreamQuality.MAX ->
            if (pref.hasYtmPremiumSession) listOf(774, 141, 251, 140, 250, 249, 139)
            else listOf(251, 141, 140, 250, 249, 139)
        StreamQuality.HIGH -> listOf(141, 251, 140, 250, 249, 139)
        StreamQuality.AUTO -> listOf(140, 251, 141, 250, 249, 139)
    }

    private fun hasRequiredAudio(map: Map<Int, String>): Boolean =
        map.keys.any { it in YtInnerTubePlayer.AUDIO_ITAG_PRIORITY }

    /**
     * Only append cpn. Do NOT invent a huge range= (breaks some CDN URLs).
     * SimpMusic uses format.contentLength when known.
     */
    private fun appendCpn(url: String, cpn: String?): String {
        if (cpn.isNullOrBlank() || url.contains("cpn=")) return url
        val sep = if (url.contains("?")) "&" else "?"
        return "${url}${sep}cpn=$cpn"
    }

    private fun isLikelyPlayableUrl(url: String): Boolean {
        if (!url.startsWith("http")) return false
        // reject obvious HTML watch pages
        if (url.contains("youtube.com/watch")) return false
        return true
    }

    private fun streamHeaders(): Map<String, String> = mapOf(
        "User-Agent" to NewPipeDownloader.USER_AGENT,
        "Referer" to "https://www.youtube.com",
        "Origin" to "https://www.youtube.com",
    )

    private fun mimeForItag(itag: Int): String = when (itag) {
        141, 140, 139 -> "audio/mp4"
        251, 250, 249 -> "audio/webm"
        else -> "audio/mp4"
    }

    private fun labelForItag(itag: Int): String = when (itag) {
        774 -> "high/774"
        141 -> "256kbps/m4a"
        251 -> "160kbps/opus"
        140 -> "128kbps/m4a"
        250 -> "70kbps/opus"
        249 -> "50kbps/opus"
        139 -> "48kbps/m4a"
        else -> "itag/$itag"
    }

    companion object {
        private const val TAG = "YtmStreamResolver"
    }
}
