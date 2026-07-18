package com.openwave.music.features.artist

import android.util.Log
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.RecentArtist
import com.openwave.music.data.source.newpipe.NewPipeBootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolve a bare artist name to a YouTube Music / YouTube channel profile
 * (display name, avatar, channel id) for Profile history chips.
 */
@Singleton
class ArtistProfileResolver @Inject constructor(
    private val newPipe: NewPipeBootstrap,
) {
    data class Resolved(
        val name: String,
        val channelId: String?,
        val imageUrl: String?,
    )

    private val cache = ConcurrentHashMap<String, Resolved>()
    private val mutex = Mutex()

    suspend fun enrich(artists: List<RecentArtist>): List<RecentArtist> =
        withContext(Dispatchers.IO) {
            if (artists.isEmpty()) return@withContext artists
            newPipe.ensureInit()
            coroutineScope {
                artists.map { a ->
                    async {
                        val resolved = resolve(a.name)
                        if (resolved == null) a
                        else a.copy(
                            name = resolved.name.ifBlank { a.name },
                            coverUrl = resolved.imageUrl ?: a.coverUrl,
                            channelId = resolved.channelId ?: a.channelId,
                        )
                    }
                }.awaitAll()
            }
        }

    suspend fun resolve(name: String): Resolved? {
        val key = name.trim().lowercase(Locale.US)
        if (key.isBlank()) return null
        cache[key]?.let { return it }
        return mutex.withLock {
            cache[key]?.let { return it }
            val found = runCatching { searchArtist(name.trim()) }
                .onFailure { Log.w(TAG, "resolve '$name': ${it.message}") }
                .getOrNull()
            if (found != null) cache[key] = found
            found
        }
    }

    private fun searchArtist(name: String): Resolved? {
        val service = ServiceList.YouTube
        val factory = service.searchQHFactory
        val available = factory.availableContentFilter?.toList().orEmpty()
        val filters = listOf(
            listOf("music_artists"),
            listOf("channels"),
            emptyList(),
        )
        for (filter in filters) {
            if (filter.isNotEmpty() && available.isNotEmpty() &&
                filter.any { it !in available }
            ) {
                continue
            }
            val handler = runCatching {
                if (filter.isEmpty()) factory.fromQuery(name)
                else factory.fromQuery(name, filter, "")
            }.getOrNull() ?: continue
            val info = runCatching { SearchInfo.getInfo(service, handler) }.getOrNull() ?: continue

            val channelHit = info.relatedItems.firstOrNull {
                it.infoType == InfoItem.InfoType.CHANNEL &&
                    namesMatch(it.name.orEmpty(), name)
            } ?: info.relatedItems.firstOrNull {
                it.infoType == InfoItem.InfoType.CHANNEL
            }

            if (channelHit != null) {
                val url = channelHit.url.orEmpty()
                val channelId = extractChannelId(url)
                val avatar = channelHit.thumbnails
                    ?.maxByOrNull { it.height * it.width }
                    ?.url
                    ?: channelHit.thumbnails?.firstOrNull()?.url
                // Prefer live channel meta when we have a stable id
                if (channelId != null) {
                    val ch = runCatching {
                        ChannelInfo.getInfo(service, "https://www.youtube.com/channel/$channelId")
                    }.getOrNull()
                    if (ch != null) {
                        return Resolved(
                            name = ch.name.orEmpty().ifBlank { channelHit.name.orEmpty().ifBlank { name } },
                            channelId = channelId,
                            imageUrl = ch.avatars?.maxByOrNull { it.height * it.width }?.url
                                ?: ch.avatars?.firstOrNull()?.url
                                ?: avatar,
                        )
                    }
                }
                return Resolved(
                    name = channelHit.name.orEmpty().ifBlank { name },
                    channelId = channelId,
                    imageUrl = avatar,
                )
            }

            // Fallback: music artist-like stream item uploader
            val stream = info.relatedItems.firstOrNull {
                it.infoType == InfoItem.InfoType.STREAM &&
                    namesMatch(
                        (it as? org.schabi.newpipe.extractor.stream.StreamInfoItem)
                            ?.uploaderName.orEmpty(),
                        name,
                    )
            } as? org.schabi.newpipe.extractor.stream.StreamInfoItem
            if (stream != null) {
                return Resolved(
                    name = stream.uploaderName.orEmpty().ifBlank { name },
                    channelId = extractChannelId(stream.uploaderUrl),
                    imageUrl = stream.uploaderAvatars?.firstOrNull()?.url
                        ?: stream.thumbnails?.firstOrNull()?.url,
                )
            }
        }
        return null
    }

    private fun namesMatch(a: String, b: String): Boolean {
        val x = a.trim().lowercase(Locale.US)
        val y = b.trim().lowercase(Locale.US)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    private fun extractChannelId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        Regex("channel/(UC[\\w-]{20,})").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        if (url.startsWith("UC") && url.length >= 22) return url
        return null
    }

    companion object {
        private const val TAG = "ArtistProfile"
    }
}
