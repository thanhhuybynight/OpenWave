package com.openwave.music.core.domain

/**
 * UI helpers for track list subtitles.
 *
 * - YouTube Music: `Artist - Album|Single - YouTube Music`
 * - SoundCloud: `UploaderUsername - SoundCloud`
 */
object TrackDisplay {
    fun subtitle(track: Track): String {
        return when (track.source) {
            MusicSource.SOUNDCLOUD -> {
                val uploader = track.artists
                    .map { it.name.trim() }
                    .firstOrNull { it.isNotBlank() }
                    ?: "Unknown"
                "$uploader - SoundCloud"
            }
            else -> {
                val artist = track.artists
                    .map { it.name.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                    .ifBlank { "Unknown" }
                val album = track.album?.title?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Single"
                val source = SourcePolicy.displayName(track.source)
                "$artist - $album - $source"
            }
        }
    }
}
