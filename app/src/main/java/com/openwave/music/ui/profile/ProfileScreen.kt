package com.openwave.music.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.openwave.music.core.domain.RecentArtist
import com.openwave.music.core.domain.RecentPlay
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.UserProfile
import com.openwave.music.presentation.ProfileViewModel
import java.util.Locale

@Composable
fun ProfileScreen(
    onPlayTrack: (Track) -> Unit,
    onArtistClick: (name: String) -> Unit,
    onOpenSettings: () -> Unit = {},
    vm: ProfileViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val recentPlays by vm.recentPlays.collectAsStateWithLifecycle()
    val recentArtists by vm.recentArtists.collectAsStateWithLifecycle()
    var showNameDialog by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) vm.setAvatarUri(uri.toString())
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Profile",
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = "Cài đặt",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        item {
            ProfileHeader(
                profile = profile,
                onEditName = { showNameDialog = true },
                onEditAvatar = { pickImage.launch("image/*") },
            )
        }

        item {
            SectionTitle("Vừa nghe")
        }
        if (recentPlays.isEmpty()) {
            // titles only — no empty copy
        } else {
            items(recentPlays, key = { "play-${it.trackId}-${it.lastPlayedAtMs}" }) { play ->
                RecentTrackRow(
                    play = play,
                    onClick = { onPlayTrack(vm.toTrack(play)) },
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("Nghệ sĩ đã nghe")
        }
        if (recentArtists.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(recentArtists, key = { it.name }) { artist ->
                        RecentArtistChip(
                            artist = artist,
                            onClick = { onArtistClick(artist.name) },
                        )
                    }
                }
            }
        }
    }

    if (showNameDialog) {
        EditNameDialog(
            current = profile.displayName,
            onDismiss = { showNameDialog = false },
            onConfirm = { name ->
                vm.setDisplayName(name)
                showNameDialog = false
            },
        )
    }
}

@Composable
private fun ProfileHeader(
    profile: UserProfile,
    onEditName: () -> Unit,
    onEditAvatar: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(scheme.primaryContainer)
                    .clickable(onClick = onEditAvatar),
                contentAlignment = Alignment.Center,
            ) {
                if (profile.avatarUri != null) {
                    AsyncImage(
                        model = profile.avatarUri,
                        contentDescription = profile.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Outlined.Person,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = scheme.onPrimaryContainer,
                    )
                }
            }
            Surface(
                onClick = onEditAvatar,
                shape = CircleShape,
                color = scheme.primary,
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "Đổi ảnh",
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.headlineMedium,
                color = scheme.onSurface,
            )
            IconButton(onClick = onEditName) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "Đổi tên",
                    tint = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun RecentTrackRow(
    play: RecentPlay,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (play.coverUrl != null) {
                AsyncImage(
                    model = play.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    play.title.take(1).uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.primary,
                )
            }
        }
        Text(
            text = play.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RecentArtistChip(
    artist: RecentArtist,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(88.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (artist.coverUrl != null) {
                AsyncImage(
                    model = artist.coverUrl,
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = artist.name.take(1).uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = scheme.onSurface,
        )
    }
}

@Composable
private fun EditNameDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tên hiển thị") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("Lưu") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Huỷ") }
        },
    )
}
