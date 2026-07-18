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
import org.schabi.newpipe.extractor.stream.StreamInfo as NpStreamInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SimpMusic-style stream resolution:
 * 1. InnerTube `player` (WEB_REMIX) for playability + optional direct URLs
 * 2. NewPipe `StreamInfo.getInfo` (music.youtube.com then youtube.com) → itag→URL map
 * 3. Pick audio itag by quality preference
 * 4. Append cpn + range like StreamRepositoryImpl
 */
@Singleton
class YtmStreamResolver @Inject constructor(
    private val innerTube: YtInnerTubePlayer,
    private val newPipe: NewPipeBootstrap,
    private val qualitySelector: StreamQualitySelector,
) {

    fun resolveAudio(videoId: String): StreamInfo? {
        newPipe.ensureInit()
        val id = YouTubeMusicSourceClient.normalizeId(videoId)

        val player = runCatching { innerTube.player(id) }
            .onFailure { Log.w(TAG, "innertube: ${it.message}") }
            .getOrNull()

        if (player != null && player.status != "OK" && player.status != "UNKNOWN") {
            Log.w(TAG, "playability=${player.status} for $id")
            // still try NewPipe; some statuses still have streams via extractor
        }

        val itagMap = linkedMapOf<Int, String>()
        // Prefer NewPipe-resolved URLs (SimpMusic merges these onto formats)
        collectNewPipeItags(id, preferMusicHost = true, into = itagMap)
        if (!hasRequiredAudio(itagMap)) {
            collectNewPipeItags(id, preferMusicHost = false, into = itagMap)
        }
        // Fill gaps from InnerTube direct urls
        player?.urlsByItag?.forEach { (itag, url) ->
            if (url.isNotBlank()) itagMap.putIfAbsent(itag, url)
        }

        if (itagMap.isEmpty()) {
            // Last resort: NewPipe pick best audio without itag map
            return resolveNewPipeBestAudio(id, player?.cpn)
        }

        val preferred = preferredItags(qualitySelector.preference)
        val chosenItag = preferred.firstOrNull { it in itagMap }
            ?: itagMap.keys.firstOrNull { it in YtInnerTubePlayer.AUDIO_ITAG_PRIORITY }
            ?: itagMap.keys.firstOrNull()
            ?: return null

        val baseUrl = itagMap[chosenItag] ?: return null
        val cpn = player?.cpn
        val url = appendPlaybackParams(baseUrl, cpn)

        // HEAD / range sanity: skip if clearly empty
        if (url.isBlank()) return null

        val expiresMs = player?.expiresInSeconds
            ?.takeIf { it > 0 }
            ?.let { System.currentTimeMillis() + it * 1000L }
            ?: (System.currentTimeMillis() + 4 * 60 * 60_000L)

        Log.d(TAG, "resolved $id itag=$chosenItag cpn=${cpn != null} mapSize=${itagMap.size}")
        return StreamInfo(
            url = url,
            mimeType = mimeForItag(chosenItag),
            qualityLabel = labelForItag(chosenItag),
            expiresAtEpochMs = expiresMs,
            headers = mapOf(
                "User-Agent" to NewPipeDownloader.USER_AGENT,
                "Referer" to "https://music.youtube.com",
                "Origin" to "https://music.youtube.com",
            ),
        )
    }

    private fun collectNewPipeItags(
        videoId: String,
        preferMusicHost: Boolean,
        into: MutableMap<Int, String>,
    ) {
        val watch = if (preferMusicHost) {
            "https://music.youtube.com/watch?v=$videoId"
        } else {
            "https://www.youtube.com/watch?v=$videoId"
        }
        runCatching {
            val info = NpStreamInfo.getInfo(ServiceList.YouTube, watch)
            val streams = buildList {
                addAll(info.audioStreams.orEmpty())
                // video-only not needed for audio app; skip heavy list
            }
            for (s in streams) {
                val itag = s.itag
                val content = s.content
                if (itag > 0 && !content.isNullOrBlank()) {
                    into[itag] = content
                }
            }
        }.onFailure {
            Log.w(TAG, "NewPipe ${if (preferMusicHost) "music" else "www"}: ${it.message}")
        }
    }

    private fun resolveNewPipeBestAudio(videoId: String, cpn: String?): StreamInfo? {
        val urls = listOf(
            "https://music.youtube.com/watch?v=$videoId",
            "https://www.youtube.com/watch?v=$videoId",
        )
        for (u in urls) {
            val info = runCatching { NpStreamInfo.getInfo(ServiceList.YouTube, u) }.getOrNull()
                ?: continue
            val audio = pickAudioStream(info.audioStreams.orEmpty(), qualitySelector.preference)
                ?: continue
            val content = audio.content ?: continue
            return StreamInfo(
                url = appendPlaybackParams(content, cpn),
                mimeType = audio.format?.mimeType ?: "audio/webm",
                qualityLabel = "${audio.averageBitrate}kbps",
                expiresAtEpochMs = System.currentTimeMillis() + 4 * 60 * 60_000L,
                headers = mapOf(
                    "User-Agent" to NewPipeDownloader.USER_AGENT,
                    "Referer" to "https://www.youtube.com",
                ),
            )
        }
        return null
    }

    private fun pickAudioStream(
        candidates: List<AudioStream>,
        pref: QualityPreference,
    ): AudioStream? {
        val usable = candidates.filter { !it.content.isNullOrBlank() }
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

    private fun preferredItags(pref: QualityPreference): List<Int> {
        return when (pref.preferred) {
            StreamQuality.MAX ->
                if (pref.hasYtmPremiumSession) listOf(774, 141, 251, 140, 250, 249)
                else listOf(251, 141, 140, 250, 249)
            StreamQuality.HIGH -> listOf(141, 251, 140, 250, 249)
            StreamQuality.AUTO -> listOf(140, 251, 141, 250, 249)
        }
    }

    private fun hasRequiredAudio(map: Map<Int, String>): Boolean =
        map.keys.any { it in YtInnerTubePlayer.AUDIO_ITAG_PRIORITY }

    private fun appendPlaybackParams(url: String, cpn: String?): String {
        // SimpMusic: format.url + "&cpn=$cpn&range=0-$contentLength"
        val sep = if (url.contains("?")) "&" else "?"
        val withCpn = if (!cpn.isNullOrBlank() && !url.contains("cpn=")) {
            "${url}${sep}cpn=$cpn"
        } else {
            url
        }
        return if (withCpn.contains("range=")) {
            withCpn
        } else {
            val s = if (withCpn.contains("?")) "&" else "?"
            "${withCpn}${s}range=0-100000000"
        }
    }

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
