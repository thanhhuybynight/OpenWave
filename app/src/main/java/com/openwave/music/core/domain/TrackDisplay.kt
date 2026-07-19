package com.openwave.music.core.domain

/**
 * UI helpers for track list subtitles.
 *
 * Format: `Artist - Album|Single - Source`
 * e.g. `Kendrick Lamar - DAMN. - YouTube Music`
 */
object TrackDisplay {
    fun subtitle(track: Track): String {
        val artist = track.artists
            .map { it.name.trim() }
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifBlank { "Unknown" }
        val album = track.album?.title?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Single"
        val source = SourcePolicy.displayName(track.source)
        return "$artist - $album - $source"
    }
}
