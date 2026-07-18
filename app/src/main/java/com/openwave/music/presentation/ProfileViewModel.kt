package com.openwave.music.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.RecentArtist
import com.openwave.music.core.domain.RecentPlay
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.UserProfile
import com.openwave.music.features.LibraryRepository
import com.openwave.music.features.UserProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepo: UserProfileRepository,
    private val library: LibraryRepository,
) : ViewModel() {

    val profile: StateFlow<UserProfile> = profileRepo.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserProfile())

    val recentPlays: StateFlow<List<RecentPlay>> = library.recentPlays(40)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentArtists: StateFlow<List<RecentArtist>> = library.recentArtists(40)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setDisplayName(name: String) {
        viewModelScope.launch { profileRepo.updateDisplayName(name) }
    }

    fun setAvatarUri(uri: String?) {
        viewModelScope.launch { profileRepo.updateAvatarUri(uri) }
    }

    fun toTrack(play: RecentPlay): Track = Track(
        id = play.trackId,
        title = play.title,
        artists = listOf(
            com.openwave.music.core.domain.Artist(
                id = "yt-${play.artist.hashCode()}",
                name = play.artist.ifBlank { "Unknown" },
                source = play.source,
            ),
        ),
        source = play.source.takeIf { it != MusicSource.UNKNOWN } ?: MusicSource.YOUTUBE_MUSIC,
        coverUrl = play.coverUrl,
    )
}
