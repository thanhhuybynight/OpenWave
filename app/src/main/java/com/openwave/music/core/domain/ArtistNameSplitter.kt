package com.openwave.music.core.domain

/**
 * Split a stored artist field into individual performer names.
 *
 * Preferred storage uses [SEP] between names (set when recording plays).
 * Legacy / external strings may use commas, "and"/"và", feat., etc.
 */
object ArtistNameSplitter {
    /** Unit-separator style delimiter used when we control the write path. */
    const val SEP = "\u001E"

    private val looseSeps = Regex(
        """\s*(?:,|;|/|\||\s+&\s+|\s+x\s+|\s+X\s+|\s+và\s+|\s+and\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+with\s+)\s*""",
        RegexOption.IGNORE_CASE,
    )

    fun encode(names: List<String>): String =
        names.map { it.trim() }.filter { it.isNotBlank() }.joinToString(SEP)

    fun encodeFromArtists(artists: List<Artist>): String =
        encode(artists.map { it.name })

    fun split(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val s = raw.trim()
        val parts = if (s.contains(SEP)) {
            s.split(SEP)
        } else {
            s.split(looseSeps)
        }
        return parts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it.length >= 2 }
            .distinctBy { it.lowercase() }
    }

    /** Human-readable join for UI titles if ever needed. */
    fun display(raw: String?): String =
        split(raw).joinToString(", ").ifBlank { raw.orEmpty() }
}
