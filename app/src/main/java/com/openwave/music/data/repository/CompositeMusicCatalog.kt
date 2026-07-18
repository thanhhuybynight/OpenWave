package com.openwave.music.data.repository

import com.openwave.music.core.domain.MusicCatalog
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.MusicSourceClient
import com.openwave.music.core.domain.SearchResult
import com.openwave.music.core.domain.SourcePolicy
import com.openwave.music.core.domain.StreamInfo
import com.openwave.music.core.domain.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject

class CompositeMusicCatalog @Inject constructor(
    private val clients: Set<@JvmSuppressWildcards MusicSourceClient>,
) : MusicCatalog {

    private fun clientFor(source: MusicSource): MusicSourceClient? =
        clients.firstOrNull { it.source == source }

    override suspend fun search(query: String, sources: Set<MusicSource>?): SearchResult =
        coroutineScope {
            val active = clients.filter { client ->
                sources?.contains(client.source) != false &&
                    client.supportsAnonymousPlayback
            }
            val partials = active.map { client ->
                async {
                    runCatching { client.search(query) }.getOrElse { SearchResult() }
                }
            }.awaitAll()

            SearchResult(
                tracks = partials.flatMap { it.tracks },
                albums = partials.flatMap { it.albums },
                artists = partials.flatMap { it.artists },
                playlists = partials.flatMap { it.playlists },
            )
        }

    override suspend fun resolveStream(track: Track): StreamInfo {
        require(SourcePolicy.canStreamAnonymously(track.source)) {
            "Source ${track.source} does not support anonymous playback. " +
                "Use a licensed path or free alternative (YouTube Music / SoundCloud)."
        }
        val client = clientFor(track.source)
            ?: error("No client registered for ${track.source}")
        return client.getStream(track)
            ?: error("Could not resolve stream for ${track.id}")
    }
}
