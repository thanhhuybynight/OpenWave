package com.openwave.music.data.repository

import com.openwave.music.core.domain.AggregatorConfig
import com.openwave.music.core.domain.FastMusicCatalog
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.MusicSourceClient
import com.openwave.music.core.domain.SearchBatch
import com.openwave.music.core.domain.SearchResult
import com.openwave.music.core.domain.SourcePolicy
import com.openwave.music.core.domain.StreamInfo
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.UnifiedTrack
import com.openwave.music.data.cache.StreamUrlCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Multi-source hub optimized for **latency first**:
 * - Parallel search, progressive [SearchBatch] emissions
 * - Per-source timeout so one dead API never freezes the UI
 * - Stream cache + race across alternate sources on play
 * - Dedupe by normalized title+artist so one song ≠ four rows
 */
@Singleton
class FastMusicCatalogImpl @Inject constructor(
    private val clients: Set<@JvmSuppressWildcards MusicSourceClient>,
    private val streamCache: StreamUrlCache,
    override val config: AggregatorConfig = AggregatorConfig(),
) : FastMusicCatalog {

    private fun clientFor(source: MusicSource): MusicSourceClient? =
        clients.firstOrNull { it.source == source }

    private fun activeClients(sources: Set<MusicSource>?): List<MusicSourceClient> {
        val ordered = config.sourcePriority
        return clients
            .filter { c ->
                (sources == null || c.source in sources) &&
                    // Search may include metadata-only sources; stream gated later
                    (c.supportsAnonymousPlayback ||
                        c.source == MusicSource.SPOTIFY_METADATA ||
                        c.source == MusicSource.APPLE_MUSIC_METADATA)
            }
            .sortedBy { ordered.indexOf(it.source).let { i -> if (i < 0) 99 else i } }
    }

    // ── Search ──────────────────────────────────────────────────────────────

    override suspend fun search(query: String, sources: Set<MusicSource>?): SearchResult {
        var last = SearchBatch(query = query)
        searchProgressive(query, sources).collect { last = it }
        return SearchResult(
            tracks = last.tracks.map { it.track },
            albums = emptyList(),
            artists = emptyList(),
            playlists = emptyList(),
        )
    }

    override fun searchProgressive(
        query: String,
        sources: Set<MusicSource>?,
    ): Flow<SearchBatch> = channelFlow {
        val q = query.trim()
        if (q.isEmpty()) {
            send(SearchBatch(query = q, isComplete = true))
            return@channelFlow
        }

        val targets = activeClients(sources)
        if (targets.isEmpty()) {
            send(SearchBatch(query = q, isComplete = true))
            return@channelFlow
        }

        val pending = ConcurrentHashMap.newKeySet<MusicSource>().apply {
            addAll(targets.map { it.source })
        }
        val completed = ConcurrentHashMap.newKeySet<MusicSource>()
        val errors = ConcurrentHashMap<MusicSource, String>()
        val rawTracks = ConcurrentHashMap<MusicSource, List<Track>>()

        fun snapshot(done: Boolean = false): SearchBatch {
            val unified = mergeAndRank(q, rawTracks.values.flatten())
            return SearchBatch(
                query = q,
                tracks = unified,
                completedSources = completed.toSet(),
                pendingSources = pending.toSet(),
                isComplete = done || pending.isEmpty(),
                errorBySource = errors.toMap(),
            )
        }

        // Immediate empty shell so UI can show "searching…"
        send(snapshot())

        val results = Channel<Pair<MusicSource, Result<SearchResult>>>(Channel.BUFFERED)

        targets.forEach { client ->
            launch(Dispatchers.IO) {
                val outcome = runCatching {
                    withTimeoutOrNull(config.searchTimeoutMs) {
                        client.search(q, config.maxResultsPerSource)
                    } ?: SearchResult() // timeout → empty, not crash
                }
                results.send(client.source to outcome)
            }
        }

        repeat(targets.size) {
            val (source, outcome) = results.receive()
            pending.remove(source)
            completed.add(source)
            outcome.fold(
                onSuccess = { rawTracks[source] = it.tracks },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    errors[source] = e.message ?: "error"
                    rawTracks[source] = emptyList()
                },
            )
            send(snapshot(done = pending.isEmpty()))
        }
    }.flowOn(Dispatchers.Default)

    // ── Stream ──────────────────────────────────────────────────────────────

    override suspend fun resolveStream(track: Track): StreamInfo =
        resolveStreamFast(track)

    override suspend fun resolveStreamFast(
        track: Track,
        alternates: List<Track>,
    ): StreamInfo = withContext(Dispatchers.IO) {
        val candidates = buildList {
            add(track)
            addAll(alternates)
        }.distinctBy { "${it.source}:${it.id}" }
            .filter { SourcePolicy.canStreamAnonymously(it.source) }
            .sortedBy {
                config.sourcePriority.indexOf(it.source).let { i -> if (i < 0) 99 else i }
            }

        require(candidates.isNotEmpty()) {
            "No anonymous stream source for ${track.title}. " +
                "Metadata-only hits need a free alternate (YTM / SoundCloud)."
        }

        // 1) Cache hits
        for (c in candidates) {
            val key = StreamUrlCache.key(c.source.name, c.id)
            streamCache.get(key)?.let { return@withContext it }
            c.streamUrl?.let { url ->
                val info = StreamInfo(url = url)
                streamCache.put(key, info, config.streamCacheTtlMs)
                return@withContext info
            }
        }

        // 2) Race: first successful resolve wins; cancel the rest
        val raced: StreamInfo? = coroutineScope {
            val winnerCh = Channel<Pair<Track, StreamInfo>>(capacity = 1)
            val jobs = candidates.map { c ->
                launch {
                    val client = clientFor(c.source) ?: return@launch
                    val info = runCatching {
                        withTimeoutOrNull(config.streamResolveTimeoutMs) {
                            client.getStream(c)
                        }
                    }.getOrNull() ?: return@launch
                    winnerCh.trySend(c to info)
                }
            }

            val winner = withTimeoutOrNull(config.streamResolveTimeoutMs + 500L) {
                winnerCh.receive()
            }
            jobs.forEach { it.cancel() }
            winnerCh.close()

            if (winner != null) {
                val (c, info) = winner
                streamCache.put(
                    StreamUrlCache.key(c.source.name, c.id),
                    info,
                    config.streamCacheTtlMs,
                )
                info
            } else {
                null
            }
        }

        raced ?: error("Could not resolve stream for \"${track.title}\" on any free source")
    }

    override suspend fun prefetchStream(track: Track) {
        if (!SourcePolicy.canStreamAnonymously(track.source)) return
        val key = StreamUrlCache.key(track.source.name, track.id)
        if (streamCache.get(key) != null) return
        runCatching { resolveStreamFast(track) }
    }

    override fun invalidateStreamCache(trackId: String?) {
        if (trackId == null) streamCache.clear()
        // Fine-grained invalidation would need reverse index; full clear is fine for MVP
        else streamCache.clear()
    }

    // ── Ranking / dedupe ────────────────────────────────────────────────────

    private fun mergeAndRank(query: String, tracks: List<Track>): List<UnifiedTrack> {
        if (tracks.isEmpty()) return emptyList()
        val groups = linkedMapOf<String, MutableList<Track>>()
        for (t in tracks) {
            val k = dedupeKey(t)
            groups.getOrPut(k) { mutableListOf() }.add(t)
        }

        val qNorm = normalize(query)
        return groups.values.map { group ->
            val ordered = group.sortedBy {
                config.sourcePriority.indexOf(it.source).let { i -> if (i < 0) 99 else i }
            }
            val primary = ordered.first()
            val score = relevance(qNorm, primary)
            UnifiedTrack(
                track = primary,
                alternates = ordered.drop(1),
                relevanceScore = score,
            )
        }.sortedByDescending { it.relevanceScore }
    }

    private fun dedupeKey(t: Track): String {
        val title = normalize(t.title)
        val artist = normalize(t.artists.firstOrNull()?.name.orEmpty())
        return "$title|$artist"
    }

    private fun normalize(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("\\[.*?]|\\(.*?\\)"), "")
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun relevance(query: String, track: Track): Float {
        val title = normalize(track.title)
        val artist = normalize(track.artists.joinToString(" ") { it.name })
        val hay = "$title $artist"
        var score = 0f
        if (title == query) score += 100f
        if (title.startsWith(query)) score += 40f
        if (hay.contains(query)) score += 20f
        // Prefer streamable primary over metadata-only
        if (SourcePolicy.canStreamAnonymously(track.source)) score += 10f
        // Tiny priority boost so YTM wins ties
        val p = config.sourcePriority.indexOf(track.source)
        if (p >= 0) score += max(0, 5 - p).toFloat()
        return score
    }
}
