package com.openwave.music.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.ArtistNameSplitter
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.RecentArtist
import com.openwave.music.core.domain.RecentPlay
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.UserProfile
import com.openwave.music.data.source.youtube.YouTubeMusicSourceClient
import com.openwave.music.data.source.youtube.YtmCreditsClient
import com.openwave.music.features.LibraryRepository
import com.openwave.music.features.UserProfileRepository
import com.openwave.music.features.artist.ArtistProfileResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepo: UserProfileRepository,
    private val library: LibraryRepository,
    private val ytmCredits: YtmCreditsClient,
    private val artistProfiles: ArtistProfileResolver,
) : ViewModel() {

    val profile: StateFlow<UserProfile> = profileRepo.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserProfile())

    val recentPlays: StateFlow<List<RecentPlay>> = library.recentPlays(40)
        .map { plays ->
            plays.map { p ->
                // Display-friendly names only; keep raw credits in DB for parsing
                p.copy(artist = ArtistNameSplitter.display(p.artist))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Performers credited on each recently played **video** (YTM /next longByline),
     * not a fuzzy name search. Collabs → separate chips with real UC browse ids.
     */
    val recentArtists: StateFlow<List<RecentArtist>> = library.recentPlays(30)
        .mapLatest { plays ->
            withContext(Dispatchers.IO) {
                resolvePerformersFromPlays(plays)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setDisplayName(name: String) {
        viewModelScope.launch { profileRepo.updateDisplayName(name) }
    }

    fun setAvatarUri(uri: String?) {
        viewModelScope.launch { profileRepo.updateAvatarUri(uri) }
    }

    fun toTrack(play: RecentPlay): Track {
        val credits = ArtistNameSplitter.splitDetailed(play.artist)
            .ifEmpty {
                ArtistNameSplitter.split(play.artist).map {
                    ArtistNameSplitter.Credit(it)
                }
            }
            .ifEmpty {
                listOf(ArtistNameSplitter.Credit(play.artist.ifBlank { "Unknown" }))
            }
        return Track(
            id = play.trackId,
            title = play.title,
            artists = credits.map { c ->
                Artist(
                    id = c.channelId ?: "yt-${c.name.hashCode()}",
                    name = c.name,
                    source = play.source,
                )
            },
            source = play.source.takeIf { it != MusicSource.UNKNOWN } ?: MusicSource.YOUTUBE_MUSIC,
            coverUrl = play.coverUrl,
        )
    }

    /**
     * Source of truth = YTM credits for each listened videoId.
     * Aggregate unique performers (by channelId) ordered by last listen.
     */
    private suspend fun resolvePerformersFromPlays(plays: List<RecentPlay>): List<RecentArtist> {
        data class Agg(
            var name: String,
            var channelId: String?,
            var lastPlayedAtMs: Long,
            var playCount: Int,
        )
        val map = linkedMapOf<String, Agg>()

        for (play in plays) {
            val videoId = YouTubeMusicSourceClient.extractVideoId(play.trackId)
                ?: YouTubeMusicSourceClient.extractVideoId(play.trackId.removePrefix("yt:"))
            val performers: List<Artist> = if (videoId != null) {
                val fromYtm = runCatching { ytmCredits.artistsForVideo(videoId) }
                    .onFailure { Log.w(TAG, "credits $videoId: ${it.message}") }
                    .getOrDefault(emptyList())
                if (fromYtm.isNotEmpty()) {
                    fromYtm
                } else {
                    // Fallback: structured storage / loose split — never invent first search hit
                    creditsToArtists(play)
                }
            } else {
                creditsToArtists(play)
            }

            for (a in performers) {
                val channelId = a.id.takeIf { it.startsWith("UC") }
                // Key by channel when possible so "ROSÉ" ≠ random "Rose" channel
                val key = channelId?.lowercase()
                    ?: ("name:" + a.name.trim().lowercase())
                val cur = map[key]
                if (cur == null) {
                    map[key] = Agg(
                        name = a.name,
                        channelId = channelId,
                        lastPlayedAtMs = play.lastPlayedAtMs,
                        playCount = 1,
                    )
                } else {
                    cur.playCount += 1
                    if (play.lastPlayedAtMs > cur.lastPlayedAtMs) {
                        cur.lastPlayedAtMs = play.lastPlayedAtMs
                        cur.name = a.name
                        if (channelId != null) cur.channelId = channelId
                    }
                }
            }
        }

        val base = map.values
            .map {
                RecentArtist(
                    name = it.name,
                    lastPlayedAtMs = it.lastPlayedAtMs,
                    playCount = it.playCount,
                    coverUrl = null,
                    channelId = it.channelId,
                )
            }
            .sortedByDescending { it.lastPlayedAtMs }

        // Avatars only via known channel ids (no fuzzy name lookup)
        return artistProfiles.enrich(base)
    }

    private fun creditsToArtists(play: RecentPlay): List<Artist> {
        val credits = ArtistNameSplitter.splitDetailed(play.artist)
        if (credits.isEmpty()) return emptyList()
        // Reject joined collab blobs still unsplit (single credit with &)
        if (credits.size == 1) {
            val n = credits[0].name
            if (n.contains(" & ") || n.contains(" and ") || n.contains(" và ")) {
                return ArtistNameSplitter.split(n).map { name ->
                    Artist(
                        id = "yt-${name.hashCode()}",
                        name = name,
                        source = play.source,
                    )
                }
            }
        }
        return credits.map { c ->
            Artist(
                id = c.channelId ?: "yt-${c.name.hashCode()}",
                name = c.name,
                source = play.source,
            )
        }
    }

    companion object {
        private const val TAG = "ProfileVM"
    }
}
