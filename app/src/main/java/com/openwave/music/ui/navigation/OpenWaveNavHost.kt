package com.openwave.music.ui.navigation

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
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
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openwave.music.core.domain.Track
import com.openwave.music.presentation.HomeViewModel
import com.openwave.music.presentation.LibraryViewModel
import com.openwave.music.presentation.PlayerViewModel
import com.openwave.music.ui.artist.ArtistScreen
import com.openwave.music.ui.home.HomeScreen
import com.openwave.music.ui.home.TrendingScreen
import com.openwave.music.ui.library.AddToPlaylistDialog
import com.openwave.music.ui.library.LibraryScreen
import com.openwave.music.ui.player.MiniPlayerBar
import com.openwave.music.ui.player.NowPlayingScreen
import com.openwave.music.ui.profile.ProfileScreen
import com.openwave.music.ui.profile.SoundCloudLoginScreen
import com.openwave.music.ui.profile.YouTubeLoginScreen
import com.openwave.music.presentation.SoundCloudSessionViewModel
import com.openwave.music.presentation.YouTubeSessionViewModel
import com.openwave.music.ui.search.SearchScreen
import com.openwave.music.ui.settings.SettingsScreen

private enum class RootDest(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "Home", Icons.Outlined.Home),
    Trending("trending", "Xu hướng", Icons.AutoMirrored.Outlined.TrendingUp),
    Search("search", "Search", Icons.Outlined.Search),
    Library("library", "Library", Icons.Outlined.LibraryMusic),
    Profile("profile", "Profile", Icons.Outlined.Person),
}

private val RootRoutes = RootDest.entries.map { it.route }.toSet()

private const val ArtistRoute =
    "artist?name={name}&id={id}&avatar={avatar}&channel={channel}"

private const val AnimMs = 280
private val FadeSpec = tween<Float>(durationMillis = AnimMs, easing = FastOutSlowInEasing)
private val SlideSpec = tween<IntOffset>(durationMillis = AnimMs, easing = FastOutSlowInEasing)

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

private fun routeBase(route: String?): String? =
    route?.substringBefore('?')?.substringBefore('/')

private fun tabIndex(route: String?): Int {
    val base = routeBase(route) ?: return -1
    return RootDest.entries.indexOfFirst { it.route == base }
}

private fun isRootTab(route: String?): Boolean {
    val base = routeBase(route) ?: return false
    return base in RootRoutes
}

@Composable
fun OpenWaveNavHost(
    playerVm: PlayerViewModel = hiltViewModel(),
    homeVm: HomeViewModel = hiltViewModel(),
    libraryVm: LibraryViewModel = hiltViewModel(),
    youtubeSessionVm: YouTubeSessionViewModel = hiltViewModel(),
    soundCloudSessionVm: SoundCloudSessionViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val snapshot by playerVm.snapshot.collectAsStateWithLifecycle()
    val votes by playerVm.votes.collectAsStateWithLifecycle()
    val playlists by libraryVm.playlists.collectAsStateWithLifecycle()
    val selectedPlaylistId by libraryVm.selectedPlaylistId.collectAsStateWithLifecycle()
    val isResolving by playerVm.isResolving.collectAsStateWithLifecycle()
    val playError by playerVm.playError.collectAsStateWithLifecycle()
    val sleepState by playerVm.sleepState.collectAsStateWithLifecycle()
    val autoContinue by playerVm.autoContinue.collectAsStateWithLifecycle()
    val stationBuilding by playerVm.stationBuilding.collectAsStateWithLifecycle()
    val favoriteIds by libraryVm.favoriteIds.collectAsStateWithLifecycle()
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
    val onLoginRoute = currentRoute == "youtube-login" || currentRoute == "soundcloud-login"
    val showBottomBar = !onArtistRoute && !onSettingsRoute && !onLoginRoute && !showFullPlayer

    val canGoBack = showFullPlayer ||
        addTrack != null ||
        selectedPlaylistId != null ||
        navController.previousBackStackEntry != null ||
        (isRootTab(currentRoute) && routeBase(currentRoute) != RootDest.Home.route)

    // System / gesture back: player → dialog → nested screen → playlist → home tab → exit
    BackHandler(enabled = canGoBack) {
        when {
            showFullPlayer -> showFullPlayer = false
            addTrack != null -> addTrack = null
            selectedPlaylistId != null -> libraryVm.closePlaylist()
            navController.previousBackStackEntry != null -> navController.popBackStack()
            isRootTab(currentRoute) && routeBase(currentRoute) != RootDest.Home.route -> {
                navController.navigate(RootDest.Home.route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = slideInVertically(animationSpec = SlideSpec) { it } + fadeIn(FadeSpec),
                    exit = slideOutVertically(animationSpec = SlideSpec) { it } + fadeOut(FadeSpec),
                ) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        RootDest.entries.forEach { dest ->
                            val selected = currentDestinationIs(backStack?.destination, dest.route)
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (selected && dest.route == RootDest.Library.route) {
                                        // Re-tap Library closes open playlist
                                        if (selectedPlaylistId != null) {
                                            libraryVm.closePlaylist()
                                        }
                                        return@NavigationBarItem
                                    }
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
                    enterTransition = {
                        navEnterTransition(
                            initialState.destination.route,
                            targetState.destination.route,
                        )
                    },
                    exitTransition = {
                        navExitTransition(
                            initialState.destination.route,
                            targetState.destination.route,
                        )
                    },
                    popEnterTransition = {
                        slideInHorizontally(animationSpec = SlideSpec) { -it / 5 } +
                            fadeIn(FadeSpec)
                    },
                    popExitTransition = {
                        slideOutHorizontally(animationSpec = SlideSpec) { it / 3 } +
                            fadeOut(FadeSpec)
                    },
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
                            vm = homeVm,
                        )
                    }
                    composable(RootDest.Trending.route) {
                        TrendingScreen(
                            onPlayTrack = playerVm::playTrack,
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
                            vm = homeVm,
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
                            onToggleFavorite = { track -> libraryVm.toggleFavorite(track) },
                            onArtistClick = { artist, avatar ->
                                navController.navigate(
                                    artistRoute(
                                        name = artist.name,
                                        id = artist.id,
                                        avatar = avatar ?: artist.imageUrl,
                                        channel = artist.id.takeIf { it.startsWith("UC") },
                                    ),
                                )
                            },
                            favoriteIds = favoriteIds,
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
                            soundCloudVm = soundCloudSessionVm,
                        )
                    }
                    composable(RootDest.Profile.route) {
                        ProfileScreen(
                            onPlayTrack = { playerVm.playTrack(it) },
                            onOpenSettings = {
                                navController.navigate("settings")
                            },
                        )
                    }
                    composable("youtube-login") {
                        YouTubeLoginScreen(
                            onBack = { navController.popBackStack() },
                            onLoginReady = {
                                youtubeSessionVm.finishLogin { success ->
                                    if (success) navController.popBackStack()
                                }
                            },
                        )
                    }
                    composable("soundcloud-login") {
                        SoundCloudLoginScreen(
                            onBack = { navController.popBackStack() },
                            onLoginReady = {
                                soundCloudSessionVm.finishLogin { success ->
                                    if (success) navController.popBackStack()
                                }
                            },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onYouTubeLogin = { navController.navigate("youtube-login") },
                            onSoundCloudLogin = { navController.navigate("soundcloud-login") },
                            sessionVm = youtubeSessionVm,
                            soundCloudVm = soundCloudSessionVm,
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

                AnimatedVisibility(
                    visible = snapshot.track != null && !showFullPlayer,
                    enter = slideInVertically(animationSpec = SlideSpec) { it } + fadeIn(FadeSpec),
                    exit = slideOutVertically(animationSpec = SlideSpec) { it } + fadeOut(FadeSpec),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    MiniPlayerBar(
                        snapshot = snapshot,
                        onPlayPause = playerVm::togglePlayPause,
                        onSkipPrevious = playerVm::skipPrevious,
                        onSkipNext = playerVm::skipNext,
                        onExpand = { showFullPlayer = true },
                        onSeek = playerVm::seekTo,
                        isFavorite = snapshot.track?.id in favoriteIds,
                        onToggleFavorite = {
                            snapshot.track?.let { libraryVm.toggleFavorite(it) }
                        },
                    )
                }
            }
        }

        // Full player overlays main UI with slide-up
        AnimatedVisibility(
            visible = showFullPlayer,
            enter = slideInVertically(animationSpec = SlideSpec) { it } + fadeIn(FadeSpec),
            exit = slideOutVertically(animationSpec = SlideSpec) { it } + fadeOut(FadeSpec),
        ) {
            NowPlayingScreen(
                snapshot = snapshot,
                onPlayPause = playerVm::togglePlayPause,
                onSeek = playerVm::seekTo,
                onSkipNext = playerVm::skipNext,
                onSkipPrevious = playerVm::skipPrevious,
                onCollapse = { showFullPlayer = false },
                onShuffle = playerVm::toggleShuffle,
                onRepeat = playerVm::cycleRepeat,
                onToggleAutoQueue = playerVm::toggleAutoContinue,
                autoQueueEnabled = autoContinue,
                autoQueueBuilding = stationBuilding,
                isFavorite = snapshot.track?.id in favoriteIds,
                onToggleFavorite = {
                    snapshot.track?.let { libraryVm.toggleFavorite(it) }
                },
                voteLabel = voteLabel,
                sleepState = sleepState,
                onSleepDurationMs = playerVm::startSleepTimer,
                onCancelSleep = playerVm::cancelSleepTimer,
                modifier = Modifier.fillMaxSize(),
            )
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

private fun currentDestinationIs(dest: NavDestination?, route: String): Boolean {
    if (dest == null) return false
    return dest.hierarchy.any { it.route == route || routeBase(it.route) == route }
}

private fun navEnterTransition(from: String?, to: String?) =
    when {
        // Tab ↔ tab: horizontal slide by tab order
        tabIndex(from) >= 0 && tabIndex(to) >= 0 -> {
            val dir = if (tabIndex(to) > tabIndex(from)) 1 else -1
            slideInHorizontally(animationSpec = SlideSpec) { full -> dir * full / 5 } +
                fadeIn(FadeSpec)
        }
        // Push nested (artist / settings): slide in from right
        else -> {
            slideInHorizontally(animationSpec = SlideSpec) { it / 3 } + fadeIn(FadeSpec)
        }
    }

private fun navExitTransition(from: String?, to: String?) =
    when {
        tabIndex(from) >= 0 && tabIndex(to) >= 0 -> {
            val dir = if (tabIndex(to) > tabIndex(from)) -1 else 1
            slideOutHorizontally(animationSpec = SlideSpec) { full -> dir * full / 5 } +
                fadeOut(FadeSpec)
        }
        else -> {
            slideOutHorizontally(animationSpec = SlideSpec) { -it / 5 } + fadeOut(FadeSpec)
        }
    }

