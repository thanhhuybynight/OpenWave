package com.openwave.music.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwave.music.core.domain.ArtistNameSplitter
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.RecentArtist
import com.openwave.music.core.domain.RecentPlay
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.UserProfile
import com.openwave.music.features.LibraryRepository
import com.openwave.music.features.UserProfileRepository
import com.openwave.music.features.artist.ArtistProfileResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepo: UserProfileRepository,
    private val library: LibraryRepository,
    private val artistProfiles: ArtistProfileResolver,
) : ViewModel() {

    val profile: StateFlow<UserProfile> = profileRepo.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserProfile())

    val recentPlays: StateFlow<List<RecentPlay>> = library.recentPlays(40)
        .map { plays ->
            plays.map { p ->
                p.copy(artist = ArtistNameSplitter.display(p.artist))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Artists expanded from multi-credit tracks, then enriched with YTM/YouTube
     * channel profiles (separate avatar + channel per person).
     */
    val recentArtists: StateFlow<List<RecentArtist>> = library.recentArtists(40)
        .map { raw -> artistProfiles.enrich(raw) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setDisplayName(name: String) {
        viewModelScope.launch { profileRepo.updateDisplayName(name) }
    }

    fun setAvatarUri(uri: String?) {
        viewModelScope.launch { profileRepo.updateAvatarUri(uri) }
    }

    fun toTrack(play: RecentPlay): Track {
        val names = ArtistNameSplitter.split(play.artist)
            .ifEmpty { listOf(play.artist.ifBlank { "Unknown" }) }
        return Track(
            id = play.trackId,
            title = play.title,
            artists = names.map { n ->
                com.openwave.music.core.domain.Artist(
                    id = "yt-${n.hashCode()}",
                    name = n,
                    source = play.source,
                )
            },
            source = play.source.takeIf { it != MusicSource.UNKNOWN } ?: MusicSource.YOUTUBE_MUSIC,
            coverUrl = play.coverUrl,
        )
    }
}
