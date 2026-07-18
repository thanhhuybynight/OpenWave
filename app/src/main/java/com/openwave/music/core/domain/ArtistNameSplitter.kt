package com.openwave.music.core.domain

/**
 * Split / encode artist credits.
 *
 * Preferred storage (when we control writes):
 *   `Name\u001FbrowseId` per artist, joined with [SEP]
 *   e.g. `ROSÉ\u001FUCbp…\u001EBruno Mars\u001FUCZn…`
 *
 * Legacy / loose strings may use commas, "and"/"và", feat., etc.
 */
object ArtistNameSplitter {
    /** Between artists. */
    const val SEP = "\u001E"
    /** Between name and optional channel/browse id. */
    const val ID_SEP = "\u001F"

    private val looseSeps = Regex(
        """\s*(?:,|;|/|\||\s+&\s+|\s+x\s+|\s+X\s+|\s+và\s+|\s+and\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+with\s+)\s*""",
        RegexOption.IGNORE_CASE,
    )

    data class Credit(
        val name: String,
        val channelId: String? = null,
    )

    fun encode(credits: List<Credit>): String =
        credits
            .map { it.name.trim() to it.channelId?.trim()?.takeIf { id -> id.isNotBlank() } }
            .filter { it.first.isNotBlank() }
            .joinToString(SEP) { (n, id) ->
                if (id != null) "$n$ID_SEP$id" else n
            }

    fun encodeFromArtists(artists: List<Artist>): String =
        encode(
            artists.map { a ->
                val channel = a.id.takeIf {
                    it.startsWith("UC") || (it.length > 2 && it.startsWith("UC"))
                }
                Credit(name = a.name, channelId = channel)
            },
        )

    fun splitDetailed(raw: String?): List<Credit> {
        if (raw.isNullOrBlank()) return emptyList()
        val s = raw.trim()
        val parts = if (s.contains(SEP) || s.contains(ID_SEP)) {
            s.split(SEP)
        } else {
            s.split(looseSeps)
        }
        return parts.mapNotNull { part ->
            val p = part.trim()
            if (p.isBlank() || p.length < 2) return@mapNotNull null
            val idIdx = p.indexOf(ID_SEP)
            if (idIdx > 0) {
                val name = p.substring(0, idIdx).trim()
                val id = p.substring(idIdx + 1).trim().takeIf { it.isNotBlank() }
                if (name.isBlank()) null else Credit(name, id)
            } else {
                Credit(p, null)
            }
        }.distinctBy { it.channelId ?: it.name.lowercase() }
    }

    fun split(raw: String?): List<String> =
        splitDetailed(raw).map { it.name }

    fun display(raw: String?): String =
        split(raw).joinToString(", ").ifBlank { raw.orEmpty() }
}
