package com.openwave.music.features.station

import android.util.Log
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.MusicSourceClient
import com.openwave.music.core.domain.Track
import com.openwave.music.data.source.newpipe.NewPipeBootstrap
import com.openwave.music.data.source.soundcloud.SoundCloudSourceClient
import com.openwave.music.data.source.youtube.YouTubeMusicSourceClient
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
     * @param crossplay when true, related + search may mix free sources;
     *   when false, only the seed track's source is used.
     * @return shuffled related tracks excluding [seed], size up to [limit]
     */
    suspend fun buildStation(
        seed: Track,
        limit: Int = 25,
        crossplay: Boolean = true,
    ): List<Track>
}

@Singleton
class StationRepositoryImpl @Inject constructor(
    private val newPipe: NewPipeBootstrap,
    private val clients: Set<@JvmSuppressWildcards MusicSourceClient>,
) : StationRepository {

    private val sc get() = clients.filterIsInstance<SoundCloudSourceClient>().firstOrNull()

    override suspend fun buildStation(
        seed: Track,
        limit: Int,
        crossplay: Boolean,
    ): List<Track> = withContext(Dispatchers.IO) {
        val collected = linkedMapOf<String, Track>()
        val seedSource = normalizeSource(seed.source)
        coroutineScope {
            val nativeRelated = async {
                when (seedSource) {
                    MusicSource.YOUTUBE_MUSIC -> youtubeRelated(seed)
                    MusicSource.SOUNDCLOUD -> soundCloudRelated(seed)
                    else -> emptyList()
                }
            }
            // Crossplay: also pull a side-source batch (search other platforms)
            val crossRelated = async {
                if (!crossplay) emptyList()
                else crossSourceFill(seed, seedSource, limit = (limit / 2).coerceAtLeast(6))
            }
            val searchFill = async {
                searchSimilar(seed, limit, crossplay, seedSource)
            }
            nativeRelated.await().forEach { collected.putIfAbsent(it.id, it) }
            crossRelated.await().forEach { collected.putIfAbsent(it.id, it) }
            searchFill.await().forEach { collected.putIfAbsent(it.id, it) }
        }
        collected.remove(seed.id)
        // Same-source only: drop accidental other-source hits
        val filtered = if (crossplay) {
            collected.values
        } else {
            collected.values.filter { normalizeSource(it.source) == seedSource }
        }
        val list = filtered.toMutableList()
        Collections.shuffle(list)
        // When crossplay: lightly interleave sources so SC/YTM both appear early
        val result = if (crossplay) interleaveBySource(list, limit) else list.take(limit.coerceAtLeast(1))
        Log.i(
            TAG,
            "station seed=${seed.id} src=$seedSource crossplay=$crossplay → ${result.size} " +
                "(yt=${result.count { it.source == MusicSource.YOUTUBE_MUSIC }} " +
                "sc=${result.count { it.source == MusicSource.SOUNDCLOUD }})",
        )
        result
    }

    /**
     * Prefer alternating sources in the first portion of the queue so crossplay
     * is audible quickly (still random within each source bucket).
     */
    private fun interleaveBySource(shuffled: List<Track>, limit: Int): List<Track> {
        if (shuffled.isEmpty()) return emptyList()
        val bySrc = shuffled.groupBy { normalizeSource(it.source) }.mapValues { it.value.toMutableList() }
        val order = bySrc.keys.shuffled()
        val out = ArrayList<Track>(limit)
        var progress = true
        while (out.size < limit && progress) {
            progress = false
            for (src in order) {
                val bucket = bySrc[src] ?: continue
                if (bucket.isEmpty()) continue
                out += bucket.removeAt(0)
                progress = true
                if (out.size >= limit) break
            }
        }
        return out
    }

    private fun normalizeSource(source: MusicSource): MusicSource = when (source) {
        MusicSource.SOUNDCLOUD -> MusicSource.SOUNDCLOUD
        MusicSource.LOCAL -> MusicSource.LOCAL
        else -> MusicSource.YOUTUBE_MUSIC // UNKNOWN / YTM
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

    /** Search the *other* free source(s) for artist/title when crossplay is on. */
    private suspend fun crossSourceFill(
        seed: Track,
        seedSource: MusicSource,
        limit: Int,
    ): List<Track> {
        val other = when (seedSource) {
            MusicSource.SOUNDCLOUD -> setOf(MusicSource.YOUTUBE_MUSIC)
            MusicSource.YOUTUBE_MUSIC -> setOf(MusicSource.SOUNDCLOUD)
            else -> setOf(MusicSource.YOUTUBE_MUSIC, MusicSource.SOUNDCLOUD)
        }
        return searchOnSources(seed, other, limit)
    }

    private suspend fun searchSimilar(
        seed: Track,
        limit: Int,
        crossplay: Boolean,
        seedSource: MusicSource,
    ): List<Track> {
        val sources = if (crossplay) {
            setOf(MusicSource.YOUTUBE_MUSIC, MusicSource.SOUNDCLOUD)
        } else {
            when (seedSource) {
                MusicSource.SOUNDCLOUD -> setOf(MusicSource.SOUNDCLOUD)
                else -> setOf(MusicSource.YOUTUBE_MUSIC)
            }
        }
        return searchOnSources(seed, sources, limit)
    }

    private suspend fun searchOnSources(
        seed: Track,
        sources: Set<MusicSource>,
        limit: Int,
    ): List<Track> {
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
