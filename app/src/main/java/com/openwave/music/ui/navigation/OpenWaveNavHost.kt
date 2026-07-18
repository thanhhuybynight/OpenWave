package com.openwave.music.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.openwave.music.presentation.PlayerViewModel
import com.openwave.music.ui.home.HomeScreen
import com.openwave.music.ui.player.MiniPlayerBar
import com.openwave.music.ui.player.NowPlayingScreen
import com.openwave.music.ui.search.SearchScreen

private enum class RootDest(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "Home", Icons.Outlined.Home),
    Search("search", "Search", Icons.Outlined.Search),
    Library("library", "Library", Icons.Outlined.LibraryMusic),
}

@Composable
fun OpenWaveNavHost(
    playerVm: PlayerViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val snapshot by playerVm.snapshot.collectAsStateWithLifecycle()
    var showFullPlayer by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        playerVm.connect()
    }

    if (showFullPlayer) {
        NowPlayingScreen(
            snapshot = snapshot,
            onPlayPause = playerVm::togglePlayPause,
            onSeek = playerVm::seekTo,
            onSkipNext = playerVm::skipNext,
            onSkipPrevious = playerVm::skipPrevious,
            onCollapse = { showFullPlayer = false },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    Scaffold(
        bottomBar = {
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
                        onPlayDemo = { playerVm.playDemo() },
                        onPlayTrack = { playerVm.playTrack(it) },
                    )
                }
                composable(RootDest.Search.route) {
                    SearchScreen(
                        onPlayTrack = { track -> playerVm.playTrack(track) },
                        onPlayUnified = { hit -> playerVm.playUnified(hit) },
                        onPrefetch = { track -> playerVm.prefetch(track) },
                    )
                }
                composable(RootDest.Library.route) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Library arrives in Phase 4",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (snapshot.track != null) {
                MiniPlayerBar(
                    snapshot = snapshot,
                    onPlayPause = playerVm::togglePlayPause,
                    onExpand = { showFullPlayer = true },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
