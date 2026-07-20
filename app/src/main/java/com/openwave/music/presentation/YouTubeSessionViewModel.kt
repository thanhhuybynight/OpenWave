package com.openwave.music.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwave.music.data.source.youtube.YtmAccountHomeClient
import com.openwave.music.features.browse.YtmBrowseRepository
import com.openwave.music.features.settings.YouTubeSessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class YouTubeSessionViewModel @Inject constructor(
    private val session: YouTubeSessionStore,
    private val browse: YtmBrowseRepository,
    private val accountHome: YtmAccountHomeClient,
) : ViewModel() {
    val loggedIn: StateFlow<Boolean> = session.loggedIn.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false,
    )
    val account = session.account.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )

    fun finishLogin(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = session.finishLogin()
            if (success) {
                runCatching { accountHome.account() }
                browse.invalidate()
            }
            onResult(success)
        }
    }

    fun logout() {
        viewModelScope.launch {
            session.logout()
            browse.invalidate()
        }
    }
}
