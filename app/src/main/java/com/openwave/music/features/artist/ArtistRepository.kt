package com.openwave.music.features.artist

import android.util.Log
import com.openwave.music.core.domain.Album
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.ArtistPage
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.Track
import com.openwave.music.data.source.newpipe.NewPipeBootstrap
import com.openwave.music.data.source.youtube.YouTubeMusicSourceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

interface ArtistRepository {
    /**
     * Load artist page for [name], optionally with known [channelId] / [avatarUrl].
     * Highlights = 5 most-played songs (YouTube Music); albums newest first.
     */
    suspend fun load(
        name: String,
        channelId: String? = null,
        avatarUrl: String? = null,
        artistId: String? = null,
    ): ArtistPage
}

@Singleton
class YtmArtistRepository @Inject constructor(
    private val newPipe: NewPipeBootstrap,
) : ArtistRepository {

    override suspend fun load(
        name: String,
        channelId: String?,
        avatarUrl: String?,
        artistId: String?,
    ): ArtistPage = withContext(Dispatchers.IO) {
        newPipe.ensureInit()
        val cleanName = name.trim().ifBlank { "Artist" }

        val channel = resolveChannel(cleanName, channelId)
        val avatar = avatarUrl
            ?: channel?.avatars?.maxByOrNull { it.height * it.width }?.url
            ?: channel?.avatars?.firstOrNull()?.url
        val resolvedChannelId = channelId
            ?: channel?.let { extractChannelId(it.url) }
            ?: artistId?.takeIf { it.startsWith("UC") }

        val artist = Artist(
            id = resolvedChannelId ?: artistId ?: "yt-artist-${cleanName.hashCode()}",
            name = channel?.name?.takeIf { it.isNotBlank() } ?: cleanName,
            source = MusicSource.YOUTUBE_MUSIC,
            imageUrl = avatar,
        )

        coroutineScope {
            val highlightsJob = async {
                runCatching { loadHighlights(artist.name, resolvedChannelId) }
                    .onFailure { Log.w(TAG, "highlights: ${it.message}") }
                    .getOrDefault(emptyList())
            }
            val albumsJob = async {
                runCatching { loadAlbums(artist, channel, resolvedChannelId) }
                    .onFailure { Log.w(TAG, "albums: ${it.message}") }
                    .getOrDefault(emptyList())
            }
            ArtistPage(
                artist = artist,
                channelId = resolvedChannelId,
                description = channel?.description,
                subscriberCount = channel?.subscriberCount?.takeIf { it > 0 },
                highlights = highlightsJob.await(),
                albums = albumsJob.await(),
            )
        }
    }

    private fun resolveChannel(name: String, channelId: String?): ChannelInfo? {
        // Prefer direct channel URL when we have UC… id
        if (!channelId.isNullOrBlank() && channelId.startsWith("UC")) {
            val urls = listOf(
                "https://www.youtube.com/channel/$channelId",
                "https://music.youtube.com/channel/$channelId",
            )
            for (url in urls) {
                val info = runCatching { ChannelInfo.getInfo(ServiceList.YouTube, url) }
                    .onFailure { Log.w(TAG, "channel $url: ${it.message}") }
                    .getOrNull()
                if (info != null) return info
            }
        }

        // Search for music artist / channel
        val service = ServiceList.YouTube
        val factory = service.searchQHFactory
        val available = factory.availableContentFilter?.toList().orEmpty()
        val filters = listOf(
            listOf("music_artists"),
            listOf("channels"),
            emptyList(),
        )
        for (filter in filters) {
            if (filter.isNotEmpty() && available.isNotEmpty() && filter.any { it !in available }) {
                continue
            }
            val handler = runCatching {
                if (filter.isEmpty()) factory.fromQuery(name)
                else factory.fromQuery(name, filter, "")
            }.getOrNull() ?: continue
            val info = runCatching { SearchInfo.getInfo(service, handler) }.getOrNull() ?: continue
            // Prefer channel items
            val channelItem = info.relatedItems.firstOrNull {
                it.infoType == InfoItem.InfoType.CHANNEL &&
                    it.name.equals(name, ignoreCase = true)
            } ?: info.relatedItems.firstOrNull {
                it.infoType == InfoItem.InfoType.CHANNEL &&
                    it.name.contains(name, ignoreCase = true)
            } ?: info.relatedItems.firstOrNull { it.infoType == InfoItem.InfoType.CHANNEL }

            val url = channelItem?.url
            if (!url.isNullOrBlank()) {
                val ch = runCatching { ChannelInfo.getInfo(ServiceList.YouTube, url) }.getOrNull()
                if (ch != null) return ch
            }
        }
        return null
    }

    /**
     * Top 5 songs by YouTube view count for this artist (music_songs search + filter).
     */
    private fun loadHighlights(artistName: String, channelId: String?): List<Track> {
        val service = ServiceList.YouTube
        val factory = service.searchQHFactory
        val available = factory.availableContentFilter?.toList().orEmpty()
        val queries = listOf(
            artistName,
            "$artistName official",
            "$artistName songs",
        )
        val scored = linkedMapOf<String, Pair<Track, Long>>()

        for (q in queries) {
            for (filter in listOf(listOf("music_songs"), listOf("music_videos"), emptyList())) {
                if (filter.isNotEmpty() && available.isNotEmpty() && filter.any { it !in available }) {
                    continue
                }
                val handler = runCatching {
                    if (filter.isEmpty()) factory.fromQuery(q)
                    else factory.fromQuery(q, filter, "")
                }.getOrNull() ?: continue
                val info = runCatching { SearchInfo.getInfo(service, handler) }.getOrNull() ?: continue
                for (item in info.relatedItems.filterIsInstance<StreamInfoItem>()) {
                    if (!matchesArtist(item, artistName, channelId)) continue
                    val url = item.url ?: continue
                    val id = YouTubeMusicSourceClient.extractVideoId(url) ?: continue
                    val views = item.viewCount.coerceAtLeast(0L)
                    val track = streamItemToTrack(item, artistName)
                    val prev = scored[id]
                    if (prev == null || views > prev.second) {
                        scored[id] = track to views
                    }
                }
            }
            if (scored.size >= 20) break
        }

        // Fallback: channel videos tab sorted by views
        if (scored.size < 5 && !channelId.isNullOrBlank()) {
            channelVideos(channelId).forEach { (track, views) ->
                val prev = scored[track.id]
                if (prev == null || views > prev.second) {
                    scored[track.id] = track to views
                }
            }
        }

        return scored.values
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }
            .also { Log.i(TAG, "highlights for $artistName: ${it.size}") }
    }

    private fun channelVideos(channelId: String): List<Pair<Track, Long>> {
        val url = "https://www.youtube.com/channel/$channelId"
        val channel = runCatching { ChannelInfo.getInfo(ServiceList.YouTube, url) }.getOrNull()
            ?: return emptyList()
        val tab = channel.tabs.firstOrNull { tab ->
            tab.contentFilters.any { it.equals(ChannelTabs.VIDEOS, ignoreCase = true) } ||
                tab.contentFilters.any { it.equals(ChannelTabs.TRACKS, ignoreCase = true) }
        } ?: channel.tabs.firstOrNull() ?: return emptyList()

        val tabInfo = runCatching {
            ChannelTabInfo.getInfo(ServiceList.YouTube, tab)
        }.getOrNull() ?: return emptyList()

        return tabInfo.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { item ->
            val track = streamItemToTrack(item, channel.name.orEmpty())
            track to item.viewCount.coerceAtLeast(0L)
        }
    }

    private fun loadAlbums(
        artist: Artist,
        channel: ChannelInfo?,
        channelId: String?,
    ): List<Album> {
        val collected = linkedMapOf<String, Album>()

        // 1) Channel ALBUMS tab (best for YTM artists)
        val ch = channel ?: channelId?.let {
            runCatching {
                ChannelInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/channel/$it")
            }.getOrNull()
        }
        if (ch != null) {
            val albumTab = ch.tabs.firstOrNull { tab ->
                tab.contentFilters.any { it.equals(ChannelTabs.ALBUMS, ignoreCase = true) }
            } ?: ch.tabs.firstOrNull { tab ->
                tab.contentFilters.any { it.equals(ChannelTabs.PLAYLISTS, ignoreCase = true) }
            }
            if (albumTab != null) {
                val tabInfo = runCatching {
                    ChannelTabInfo.getInfo(ServiceList.YouTube, albumTab)
                }.onFailure { Log.w(TAG, "album tab: ${it.message}") }.getOrNull()
                tabInfo?.relatedItems?.filterIsInstance<PlaylistInfoItem>()?.forEach { pl ->
                    val album = playlistItemToAlbum(pl, artist)
                    collected.putIfAbsent(album.id, album)
                }
            }
        }

        // 2) Search music_albums
        val service = ServiceList.YouTube
        val factory = service.searchQHFactory
        val available = factory.availableContentFilter?.toList().orEmpty()
        for (filter in listOf(listOf("music_albums"), listOf("playlists"))) {
            if (filter.isNotEmpty() && available.isNotEmpty() && filter.any { it !in available }) {
                continue
            }
            val handler = runCatching {
                factory.fromQuery(artist.name, filter, "")
            }.getOrNull() ?: continue
            val info = runCatching { SearchInfo.getInfo(service, handler) }.getOrNull() ?: continue
            info.relatedItems.filterIsInstance<PlaylistInfoItem>().forEach { pl ->
                val uploader = pl.uploaderName.orEmpty()
                if (uploader.isNotBlank() &&
                    !uploader.contains(artist.name, ignoreCase = true) &&
                    !artist.name.contains(uploader, ignoreCase = true)
                ) {
                    // still allow if title contains artist
                    if (!pl.name.orEmpty().contains(artist.name, ignoreCase = true)) return@forEach
                }
                val album = playlistItemToAlbum(pl, artist)
                collected.putIfAbsent(album.id, album)
            }
        }

        // Enrich release dates for sorting (first track upload as approximation)
        val albums = collected.values.toList()
        val enriched = albums.map { album ->
            if (album.releasedAtMs != null || album.year != null) return@map album
            val uri = album.sourceUri ?: return@map album
            val dateMs = runCatching { estimateAlbumReleaseMs(uri) }.getOrNull()
            if (dateMs == null) album
            else album.copy(
                releasedAtMs = dateMs,
                year = java.time.Instant.ofEpochMilli(dateMs)
                    .atZone(java.time.ZoneOffset.UTC)
                    .year,
            )
        }

        return enriched.sortedWith(
            compareByDescending<Album> { it.releasedAtMs ?: Long.MIN_VALUE }
                .thenByDescending { it.year ?: Int.MIN_VALUE }
                .thenBy { it.title.lowercase(Locale.US) },
        ).also { Log.i(TAG, "albums for ${artist.name}: ${it.size}") }
    }

    private fun estimateAlbumReleaseMs(playlistUrl: String): Long? {
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl)
        // Prefer newest stream date on the album as release approximation (albums often all same era)
        var best: Long? = null
        for (item in info.relatedItems.take(8)) {
            val d = item.uploadDate?.instant?.toEpochMilli() ?: continue
            if (best == null || d < best) best = d // earliest track ≈ release
        }
        return best
    }

    private fun playlistItemToAlbum(pl: PlaylistInfoItem, artist: Artist): Album {
        val url = pl.url.orEmpty()
        val id = extractPlaylistId(url) ?: url.ifBlank { pl.name.hashCode().toString() }
        val year = extractYear(pl.name.orEmpty())
            ?: extractYear(pl.description?.content.orEmpty())
        return Album(
            id = id,
            title = pl.name.orEmpty().ifBlank { "Album" },
            artists = listOf(artist),
            source = MusicSource.YOUTUBE_MUSIC,
            coverUrl = pl.thumbnails?.maxByOrNull { it.height * it.width }?.url
                ?: pl.thumbnails?.firstOrNull()?.url,
            year = year,
            releasedAtMs = year?.let {
                java.time.LocalDate.of(it, 6, 15)
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            },
            sourceUri = url.takeIf { it.isNotBlank() },
            trackCount = pl.streamCount.takeIf { it > 0 },
        )
    }

    private fun streamItemToTrack(item: StreamInfoItem, fallbackArtist: String): Track {
        val url = item.url.orEmpty()
        val id = YouTubeMusicSourceClient.extractVideoId(url) ?: url.hashCode().toString()
        val artistName = item.uploaderName.orEmpty().ifBlank { fallbackArtist }
        return Track(
            id = id,
            title = item.name.orEmpty().ifBlank { "Track" },
            artists = listOf(
                Artist(
                    id = extractChannelId(item.uploaderUrl) ?: "yt-${artistName.hashCode()}",
                    name = artistName,
                    source = MusicSource.YOUTUBE_MUSIC,
                    imageUrl = item.uploaderAvatars?.firstOrNull()?.url,
                ),
            ),
            durationMs = if (item.duration > 0) item.duration * 1000 else 0L,
            source = MusicSource.YOUTUBE_MUSIC,
            coverUrl = item.thumbnails?.maxByOrNull { it.height * it.width }?.url
                ?: item.thumbnails?.firstOrNull()?.url,
            sourceUri = url.takeIf { it.isNotBlank() },
        )
    }

    private fun matchesArtist(
        item: StreamInfoItem,
        artistName: String,
        channelId: String?,
    ): Boolean {
        val uploader = item.uploaderName.orEmpty()
        val title = item.name.orEmpty()
        if (!channelId.isNullOrBlank()) {
            val upUrl = item.uploaderUrl.orEmpty()
            if (upUrl.contains(channelId)) return true
        }
        if (uploader.contains(artistName, ignoreCase = true)) return true
        if (artistName.contains(uploader, ignoreCase = true) && uploader.length >= 3) return true
        // Title often "Song - Artist"
        if (title.contains(artistName, ignoreCase = true)) return true
        return false
    }

    private fun extractChannelId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        Regex("channel/(UC[\\w-]{20,})").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        if (url.startsWith("UC") && url.length >= 22) return url
        return null
    }

    private fun extractPlaylistId(url: String): String? {
        Regex("[?&]list=([\\w-]+)").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("playlist\\?list=([\\w-]+)").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("browse/([\\w%-]+)").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun extractYear(text: String): Int? {
        val m = Regex("(?:^|\\D)((?:19|20)\\d{2})(?:\\D|$)").find(text) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        return y.takeIf { it in 1950..2100 }
    }

    companion object {
        private const val TAG = "ArtistRepo"
    }
}
