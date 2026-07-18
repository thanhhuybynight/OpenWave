package com.openwave.music.core.player

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-stream HTTP headers for Media3/OkHttp (YouTube vs SoundCloud CDN).
 * Registered when a URL is resolved; applied by [streamHeadersInterceptor].
 */
object StreamRequestHeaders {
    private val byUrl = ConcurrentHashMap<String, Map<String, String>>()

    fun register(url: String, headers: Map<String, String>) {
        if (url.isBlank() || headers.isEmpty()) return
        byUrl[url] = headers
        // Also key without query for redirect variants
        val bare = url.substringBefore('?')
        if (bare != url) byUrl.putIfAbsent(bare, headers)
    }

    fun forUrl(url: String): Map<String, String> {
        byUrl[url]?.let { return it }
        val bare = url.substringBefore('?')
        byUrl[bare]?.let { return it }
        return emptyMap()
    }

    fun clear() = byUrl.clear()
}

/** Host-based defaults when no explicit map was registered. */
fun defaultHeadersForHost(host: String): Map<String, String> {
    val h = host.lowercase()
    return when {
        "sndcdn" in h || "soundcloud" in h -> mapOf(
            "User-Agent" to PlaybackService.STREAM_USER_AGENT,
            "Accept" to "*/*",
            "Origin" to "https://soundcloud.com",
            "Referer" to "https://soundcloud.com/",
        )
        "googlevideo" in h || "youtube" in h || "ytimg" in h || "ggpht" in h -> mapOf(
            "User-Agent" to PlaybackService.STREAM_USER_AGENT,
            "Accept" to "*/*",
            "Origin" to "https://music.youtube.com",
            "Referer" to "https://music.youtube.com/",
        )
        else -> mapOf(
            "User-Agent" to PlaybackService.STREAM_USER_AGENT,
            "Accept" to "*/*",
        )
    }
}
