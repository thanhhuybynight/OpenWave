package com.openwave.music.data.source

import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.BrowseItem
import com.openwave.music.core.domain.BrowseShelf
import com.openwave.music.core.domain.BrowseShelfKind
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.SearchResult
import com.openwave.music.core.domain.Track

/**
 * Rich offline-friendly demo catalog so debug APK previews UI without extractors.
 * Audio URLs: public ExoPlayer / Google test media (short clips).
 */
object DemoCatalog {

    private val streamA =
        "https://storage.googleapis.com/exoplayer-test-media-1/mp3/android-screens-japan.mp3"
    private val streamB =
        "https://storage.googleapis.com/exoplayer-test-media-0/play.mp3"

    private fun artist(id: String, name: String) =
        Artist(id = id, name = name, source = MusicSource.LOCAL)

    fun tracks(): List<Track> = listOf(
        Track(
            id = "demo-1",
            title = "Night Drive Sample",
            artists = listOf(artist("a1", "OpenWave Demo")),
            durationMs = 30_000L,
            source = MusicSource.LOCAL,
            streamUrl = streamA,
        ),
        Track(
            id = "demo-2",
            title = "Harbor Lights",
            artists = listOf(artist("a2", "Lumen River")),
            durationMs = 45_000L,
            source = MusicSource.YOUTUBE_MUSIC,
            streamUrl = streamB,
        ),
        Track(
            id = "demo-3",
            title = "Paper Kites",
            artists = listOf(artist("a3", "Field Notes")),
            durationMs = 40_000L,
            source = MusicSource.SOUNDCLOUD,
            streamUrl = streamA,
        ),
        Track(
            id = "demo-4",
            title = "Static Bloom",
            artists = listOf(artist("a4", "North Grid")),
            durationMs = 35_000L,
            source = MusicSource.YOUTUBE_MUSIC,
            streamUrl = streamB,
        ),
        Track(
            id = "demo-5",
            title = "Soft Circuit",
            artists = listOf(artist("a5", "Analog Tide")),
            durationMs = 50_000L,
            source = MusicSource.SOUNDCLOUD,
            streamUrl = streamA,
        ),
        Track(
            id = "demo-6",
            title = "Midnight Circuit",
            artists = listOf(artist("a1", "OpenWave Demo")),
            durationMs = 42_000L,
            source = MusicSource.LOCAL,
            streamUrl = streamB,
        ),
    )

    fun search(query: String, limit: Int = 20): SearchResult {
        val q = query.trim()
        val all = tracks()
        val hits = if (q.isBlank()) all else all.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.artists.any { a -> a.name.contains(q, ignoreCase = true) }
        }
        return SearchResult(tracks = hits.take(limit))
    }

    fun homeShelves(): List<BrowseShelf> {
        val all = tracks()
        return listOf(
            BrowseShelf(
                id = "home_quick",
                title = "Quick picks",
                kind = BrowseShelfKind.HOME_QUICK_PICKS,
                items = all.take(4).map { it.toBrowse() },
            ),
            BrowseShelf(
                id = "charts",
                title = "Charts",
                kind = BrowseShelfKind.CHARTS,
                items = all.drop(1).take(4).map { it.toBrowse() },
            ),
            BrowseShelf(
                id = "podcasts",
                title = "Podcasts",
                kind = BrowseShelfKind.PODCASTS,
                items = listOf(
                    BrowseItem.CategoryItem(
                        id = "pod-1",
                        title = "Dev Night",
                        subtitle = "Weekly tech audio",
                    ),
                    BrowseItem.CategoryItem(
                        id = "pod-2",
                        title = "Lo-fi Stories",
                        subtitle = "Narrative + beats",
                    ),
                ),
            ),
            BrowseShelf(
                id = "moods",
                title = "Moods & genre",
                kind = BrowseShelfKind.MOODS_AND_GENRES,
                items = listOf(
                    BrowseItem.CategoryItem("mood-chill", "Chill", "Wind down"),
                    BrowseItem.CategoryItem("mood-focus", "Focus", "Deep work"),
                    BrowseItem.CategoryItem("mood-energy", "Energy", "Move"),
                    BrowseItem.CategoryItem("mood-night", "Night", "After hours"),
                ),
            ),
        )
    }

    private fun Track.toBrowse() = BrowseItem.TrackItem(
        id = id,
        title = title,
        subtitle = artists.joinToString { it.name },
        coverUrl = coverUrl,
        track = this,
    )
}
