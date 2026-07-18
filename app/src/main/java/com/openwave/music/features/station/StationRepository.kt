package com.openwave.music.features.station

import android.util.Log
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.Track
import com.openwave.music.data.source.newpipe.NewPipeBootstrap
import com.openwave.music.data.source.soundcloud.SoundCloudSourceClient
import com.openwave.music.data.source.youtube.YouTubeMusicSourceClient
import com.openwave.music.core.domain.MusicSourceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a radio/station queue from a seed track (YT Music / SoundCloud style).
 */
interface StationRepository {
    /**
     * @return shuffled related tracks excluding [seed], size up to [limit]
     */
    suspend fun buildStation(seed: Track, limit: Int = 25): List<Track>
}

@Singleton
class StationRepositoryImpl @Inject constructor(
    private val newPipe: NewPipeBootstrap,
    private val clients: Set<@JvmSuppressWildcards MusicSourceClient>,
) : StationRepository {

    private val yt get() = clients.filterIsInstance<YouTubeMusicSourceClient>().firstOrNull()
    private val sc get() = clients.filterIsInstance<SoundCloudSourceClient>().firstOrNull()

    override suspend fun buildStation(seed: Track, limit: Int): List<Track> =
        withContext(Dispatchers.IO) {
            val collected = linkedMapOf<String, Track>()
            coroutineScope {
                val related = async {
                    when (seed.source) {
                        MusicSource.YOUTUBE_MUSIC,
                        MusicSource.UNKNOWN,
                        -> youtubeRelated(seed)
                        MusicSource.SOUNDCLOUD -> soundCloudRelated(seed)
                        MusicSource.LOCAL -> emptyList()
                        else -> emptyList()
                    }
                }
                val searchFill = async {
                    searchSimilar(seed, limit)
                }
                related.await().forEach { collected.putIfAbsent(it.id, it) }
                searchFill.await().forEach { collected.putIfAbsent(it.id, it) }
            }
            collected.remove(seed.id)
            val list = collected.values.toMutableList()
            Collections.shuffle(list)
            list.take(limit.coerceAtLeast(1))
        }

    private fun youtubeRelated(seed: Track): List<Track> {
        newPipe.ensureInit()
        val videoId = YouTubeMusicSourceClient.extractVideoId(seed.id)
            ?: YouTubeMusicSourceClient.extractVideoId(seed.sourceUri.orEmpty())
            ?: return emptyList()
        val urls = listOf(
            "https://www.youtube.com/watch?v=$videoId",
            "https://music.youtube.com/watch?v=$videoId",
        )
        for (url in urls) {
            val info = runCatching { StreamInfo.getInfo(ServiceList.YouTube, url) }
                .onFailure { Log.w(TAG, "related $url: ${it.message}") }
                .getOrNull()
                ?: continue
            val tracks = info.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { si ->
                    val u = si.url ?: return@mapNotNull null
                    val id = YouTubeMusicSourceClient.extractVideoId(u) ?: return@mapNotNull null
                    Track(
                        id = id,
                        title = si.name.orEmpty().ifBlank { return@mapNotNull null },
                        artists = listOf(
                            Artist(
                                id = "yt-${si.uploaderName.hashCode()}",
                                name = si.uploaderName.orEmpty().ifBlank { "YouTube" },
                                source = MusicSource.YOUTUBE_MUSIC,
                            ),
                        ),
                        durationMs = if (si.duration > 0) si.duration * 1000 else 0L,
                        source = MusicSource.YOUTUBE_MUSIC,
                        coverUrl = si.thumbnails?.firstOrNull()?.url,
                        sourceUri = u,
                    )
                }
            if (tracks.isNotEmpty()) {
                Log.d(TAG, "YT related ${tracks.size} for $videoId")
                return tracks
            }
        }
        return emptyList()
    }

    private suspend fun soundCloudRelated(seed: Track): List<Track> {
        val scClient = sc ?: return emptyList()
        return runCatching { scClient.relatedTracks(seed.id, limit = 30) }
            .onFailure { Log.w(TAG, "SC related: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private suspend fun searchSimilar(seed: Track, limit: Int): List<Track> {
        val artist = seed.artists.firstOrNull()?.name.orEmpty()
        val queries = buildList {
            if (artist.isNotBlank()) {
                add("$artist mix")
                add("$artist radio")
                add(artist)
            }
            add("${seed.title} ${artist}".trim())
            add(seed.title)
        }.distinct().filter { it.isNotBlank() }

        val sources = when (seed.source) {
            MusicSource.SOUNDCLOUD -> setOf(MusicSource.SOUNDCLOUD, MusicSource.YOUTUBE_MUSIC)
            MusicSource.YOUTUBE_MUSIC -> setOf(MusicSource.YOUTUBE_MUSIC, MusicSource.SOUNDCLOUD)
            else -> setOf(MusicSource.YOUTUBE_MUSIC, MusicSource.SOUNDCLOUD)
        }

        val out = linkedMapOf<String, Track>()
        for (q in queries) {
            if (out.size >= limit) break
            for (src in sources) {
                val client = clients.firstOrNull { it.source == src } ?: continue
                val hits = runCatching { client.search(q, 12) }
                    .onFailure { Log.w(TAG, "search $src '$q': ${it.message}") }
                    .getOrNull()
                    ?.tracks
                    .orEmpty()
                hits.forEach { t ->
                    if (t.id != seed.id) out.putIfAbsent(t.id, t)
                }
            }
        }
        return out.values.toList()
    }

    companion object {
        private const val TAG = "StationRepo"
    }
}
