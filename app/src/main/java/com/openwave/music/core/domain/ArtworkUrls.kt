package com.openwave.music.core.domain

/**
 * Upgrade catalog preview thumbnails to player-grade artwork.
 *
 * Search/browse often ships tiny previews (60–120px or SoundCloud `-large` 100px).
 * Now Playing / MediaSession need much larger images.
 */
object ArtworkUrls {

    /**
     * @param url existing cover URL (may be low-res)
     * @param youtubeVideoId optional 11-char video id for YT max-res fallback
     */
    fun highRes(url: String?, youtubeVideoId: String? = null): String? {
        val raw = url?.trim()?.takeIf { it.isNotBlank() }
            ?.replace("http://", "https://")

        // SoundCloud never uses YT video ids
        if (raw != null && isSoundCloud(raw)) {
            return upgradeSoundCloud(raw)
        }

        // Prefer official YT high-res frame when we know the video id
        // (search thumbs are often 60–120px googleusercontent crops)
        val vid = youtubeVideoId?.takeIf { it.length == 11 && it.matches(VIDEO_ID) }
            ?: raw?.let { extractYtImgVideoId(it) }
        if (vid != null) {
            return youtubeBest(vid)
        }

        if (raw != null) {
            if (isGoogleHosted(raw)) return upgradeGoogleHosted(raw)
            return raw
        }
        return null
    }

    /** Prefer high-res cover on a [Track] (immutable copy). */
    fun enrich(track: Track): Track {
        val videoId = when (track.source) {
            MusicSource.YOUTUBE_MUSIC, MusicSource.UNKNOWN ->
                extractVideoIdFromTrack(track)
            else -> null
        }
        val hi = highRes(track.coverUrl, videoId) ?: return track
        if (hi == track.coverUrl) return track
        val album = track.album?.let { a ->
            val aHi = highRes(a.coverUrl, videoId) ?: a.coverUrl
            if (aHi == a.coverUrl) a else a.copy(coverUrl = aHi)
        }
        return track.copy(coverUrl = hi, album = album)
    }

    fun enrichAll(tracks: List<Track>): List<Track> = tracks.map { enrich(it) }

    // ── YouTube ─────────────────────────────────────────────────────────────

    /**
     * Player-grade YT still frame. `maxresdefault` is often missing for older
     * videos; `hq720` (1280×720 when available) then `sddefault` are solid.
     * We pick hq720 as the stable high-quality default for Media3/Coil.
     */
    fun youtubeBest(videoId: String): String =
        "https://i.ytimg.com/vi/$videoId/hq720.jpg"

    /** Alternate ladder for callers that want to try larger first. */
    fun youtubeLadder(videoId: String): List<String> = listOf(
        "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
        "https://i.ytimg.com/vi/$videoId/hq720.jpg",
        "https://i.ytimg.com/vi/$videoId/sddefault.jpg",
        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
    )

    private fun isYtImg(url: String): Boolean =
        url.contains("i.ytimg.com") || url.contains("ytimg.googleusercontent.com")

    private fun extractYtImgVideoId(url: String): String? {
        // https://i.ytimg.com/vi/VIDEO_ID/hqdefault.jpg
        // https://i.ytimg.com/vi_webp/VIDEO_ID/maxresdefault.webp
        return Regex(
            """i\.ytimg\.com/vi(?:_webp)?/([a-zA-Z0-9_-]{11})/""",
        ).find(url)?.groupValues?.getOrNull(1)
    }

    private fun extractVideoIdFromTrack(track: Track): String? {
        val fromId = track.id.removePrefix("yt:").takeIf { it.length == 11 && it.matches(VIDEO_ID) }
        if (fromId != null) return fromId
        val uri = track.sourceUri.orEmpty()
        Regex("""[?&]v=([a-zA-Z0-9_-]{11})""").find(uri)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""youtu\.be/([a-zA-Z0-9_-]{11})""").find(uri)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""/vi(?:_webp)?/([a-zA-Z0-9_-]{11})/""").find(track.coverUrl.orEmpty())
            ?.groupValues?.getOrNull(1)?.let { return it }
        // bare 11-char id used across the app for YTM
        if (track.id.length == 11 && track.id.matches(VIDEO_ID)) return track.id
        return null
    }

    // ── Google-hosted (YTM musicThumbnail) ──────────────────────────────────

    private fun isGoogleHosted(url: String): Boolean =
        url.contains("googleusercontent.com") ||
            url.contains("ggpht.com") ||
            url.contains("yt3.ggpht.com") ||
            url.contains("lh3.googleusercontent.com")

    /**
     * YTM thumbs: `...=w60-h60-l90-rj` or `...=s88-c-k-c0x00ffffff-no-rj`.
     * Bump to ~square 1280 or unrestricted `s0`.
     */
    private fun upgradeGoogleHosted(url: String): String {
        var u = url
        // =wNNN-hNNN... → large square
        u = u.replace(Regex("""=w\d+-h\d+"""), "=w1280-h1280")
        // =sNNN- → =s0 (original) when small
        u = u.replace(Regex("""=s(\d{1,3})(?=[-?]|$)""")) { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            if (n in 1..512) "=s0" else m.value
        }
        // Trailing size in path variants
        u = u.replace(Regex("""/s\d{2,3}(-c)?/"""), "/s0$1/")
        return u
    }

    // ── SoundCloud ──────────────────────────────────────────────────────────

    private fun isSoundCloud(url: String): Boolean =
        url.contains("sndcdn.com") || url.contains("soundcloud.com")

    /**
     * Artwork tokens: mini, tiny, small, badge, t67x67, large (100px),
     * t300x300, crop, t500x500, original.
     * Prefer **t500x500** (reliable); fall back chain leaves original if already set.
     */
    private fun upgradeSoundCloud(url: String): String {
        if (url.contains("-original") || url.contains("-t500x500")) return url
        val tokens = listOf(
            "-t500x500",
            "-crop",
            "-t300x300",
            "-large",
            "-t67x67",
            "-badge",
            "-small",
            "-tiny",
            "-mini",
        )
        for (t in tokens) {
            if (url.contains(t)) {
                return url.replace(t, "-t500x500")
            }
        }
        // artwork-XXXX-large.jpg style without hyphen token mid-path
        return url
            .replace(Regex("""-(?:large|small|tiny|mini)(\.[a-zA-Z0-9]+)"""), "-t500x500$1")
    }

    private val VIDEO_ID = Regex("""^[a-zA-Z0-9_-]{11}$""")
}
