package com.openwave.music.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openwave.music.core.domain.Track
import com.openwave.music.presentation.LibraryViewModel
import com.openwave.music.presentation.PlayerViewModel
import com.openwave.music.ui.artist.ArtistScreen
import com.openwave.music.ui.home.HomeScreen
import com.openwave.music.ui.library.AddToPlaylistDialog
import com.openwave.music.ui.library.LibraryScreen
import com.openwave.music.ui.player.MiniPlayerBar
import com.openwave.music.ui.player.NowPlayingScreen
import com.openwave.music.ui.profile.ProfileScreen
import com.openwave.music.ui.search.SearchScreen
import com.openwave.music.ui.settings.SettingsScreen

private enum class RootDest(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "Home", Icons.Outlined.Home),
    Search("search", "Search", Icons.Outlined.Search),
    Library("library", "Library", Icons.Outlined.LibraryMusic),
    Profile("profile", "Profile", Icons.Outlined.Person),
}

private const val ArtistRoute =
    "artist?name={name}&id={id}&avatar={avatar}&channel={channel}"

private fun artistRoute(
    name: String,
    id: String = "",
    avatar: String? = null,
    channel: String? = null,
): String {
    fun e(s: String) = Uri.encode(s)
    return buildString {
        append("artist?name=").append(e(name))
        append("&id=").append(e(id))
        append("&avatar=").append(e(avatar.orEmpty()))
        append("&channel=").append(e(channel.orEmpty()))
    }
}

@Composable
fun OpenWaveNavHost(
    playerVm: PlayerViewModel = hiltViewModel(),
    libraryVm: LibraryViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val snapshot by playerVm.snapshot.collectAsStateWithLifecycle()
    val votes by playerVm.votes.collectAsStateWithLifecycle()
    val playlists by libraryVm.playlists.collectAsStateWithLifecycle()
    val isResolving by playerVm.isResolving.collectAsStateWithLifecycle()
    val playError by playerVm.playError.collectAsStateWithLifecycle()
    val sleepState by playerVm.sleepState.collectAsStateWithLifecycle()
    val crossfade by playerVm.crossfadeSettings.collectAsStateWithLifecycle()
    val stationActive by playerVm.stationActive.collectAsStateWithLifecycle()
    val stationBuilding by playerVm.stationBuilding.collectAsStateWithLifecycle()
    var showFullPlayer by remember { mutableStateOf(false) }
    var addTrack by remember { mutableStateOf<Track?>(null) }

    LaunchedEffect(Unit) {
        playerVm.connect()
    }

    val voteLabel = votes?.let { v ->
        "RYD  +${v.likes}  −${v.dislikes}"
    }

    val onArtistRoute = currentRoute?.startsWith("artist") == true
    val onSettingsRoute = currentRoute == "settings"
    val showBottomBar = !onArtistRoute && !onSettingsRoute

    if (showFullPlayer) {
        NowPlayingScreen(
            snapshot = snapshot,
            onPlayPause = playerVm::togglePlayPause,
            onSeek = playerVm::seekTo,
            onSkipNext = playerVm::skipNext,
            onSkipPrevious = playerVm::skipPrevious,
            onCollapse = { showFullPlayer = false },
            onShuffle = playerVm::toggleShuffle,
            onRepeat = playerVm::cycleRepeat,
            onStartStation = playerVm::startStationFromCurrent,
            stationActive = stationActive,
            stationBuilding = stationBuilding,
            voteLabel = voteLabel,
            sleepState = sleepState,
            crossfade = crossfade,
            onSleepDurationMs = playerVm::startSleepTimer,
            onCancelSleep = playerVm::cancelSleepTimer,
            onCrossfade = playerVm::setCrossfade,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    RootDest.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            NavHost(
                navController = navController,
                startDestination = RootDest.Home.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(RootDest.Home.route) {
                    HomeScreen(
                        onPlayTrack = { playerVm.playTrack(it) },
                        onArtistClick = { item ->
                            navController.navigate(
                                artistRoute(
                                    name = item.title,
                                    id = item.id,
                                    avatar = item.coverUrl ?: item.artist.imageUrl,
                                    channel = item.channelId,
                                ),
                            )
                        },
                        onAddToPlaylist = { addTrack = it },
                    )
                }
                composable(RootDest.Search.route) {
                    SearchScreen(
                        onPlayTrack = { track -> playerVm.playTrack(track) },
                        onPlayUnified = { hit -> playerVm.playUnified(hit) },
                        onPrefetch = { track -> playerVm.prefetch(track) },
                        onAddToPlaylist = { addTrack = it },
                        onAddToQueue = { track -> playerVm.enqueueTrack(track) },
                        onStartStation = { track ->
                            playerVm.startStation(track)
                            showFullPlayer = true
                        },
                        isResolving = isResolving || stationBuilding,
                        playError = playError,
                        onClearError = playerVm::clearPlayError,
                    )
                }
                composable(RootDest.Library.route) {
                    LibraryScreen(
                        onPlayTrack = { playerVm.playTrack(it) },
                        onPlayQueue = { tracks, index -> playerVm.playQueue(tracks, index) },
                        vm = libraryVm,
                    )
                }
                composable(RootDest.Profile.route) {
                    ProfileScreen(
                        onPlayTrack = { playerVm.playTrack(it) },
                        onArtistClick = { name, channelId ->
                            navController.navigate(
                                artistRoute(
                                    name = name,
                                    channel = channelId,
                                ),
                            )
                        },
                        onOpenSettings = {
                            navController.navigate("settings")
                        },
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = ArtistRoute,
                    arguments = listOf(
                        navArgument("name") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("id") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("avatar") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("channel") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) {
                    ArtistScreen(
                        onBack = { navController.popBackStack() },
                        onPlayTrack = { playerVm.playTrack(it) },
                        onPlayQueue = { tracks, index -> playerVm.playQueue(tracks, index) },
                    )
                }
            }

            if (snapshot.track != null) {
                MiniPlayerBar(
                    snapshot = snapshot,
                    onPlayPause = playerVm::togglePlayPause,
                    onExpand = { showFullPlayer = true },
                    onSeek = playerVm::seekTo,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    addTrack?.let { track ->
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { addTrack = null },
            onCreateAndAdd = { title ->
                libraryVm.createPlaylistAndAdd(title, track)
                addTrack = null
            },
            onPick = { id ->
                libraryVm.addToPlaylist(id, track)
                addTrack = null
            },
        )
    }
}
