package com.openwave.music.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.PlaybackProgress
import com.openwave.music.core.domain.PlaybackState
import com.openwave.music.core.domain.PlayerSnapshot
import com.openwave.music.core.domain.SleepTimerState
import com.openwave.music.core.domain.Track
import com.openwave.music.core.domain.TrackDisplay
import com.openwave.music.ui.theme.OpenWaveTheme
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Full-screen now-playing surface.
 *
 * Taste notes (native Material You, not web landing rules):
 * - Artwork is the hero; large radius (28dp), soft scrim under controls
 * - Dynamic Color drives primary / play FAB
 * - Spring scale on primary transport (tactile press + play/pause morph)
 * - No AI-purple glow, no decorative status dots, no version chrome
 * - Shape lock: artwork extraLarge, FAB circle, secondary controls soft
 */
@Composable
fun NowPlayingScreen(
    snapshot: PlayerSnapshot,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onCollapse: () -> Unit,
    onShuffle: () -> Unit = {},
    onRepeat: () -> Unit = {},
    onToggleAutoQueue: () -> Unit = {},
    autoQueueEnabled: Boolean = true,
    autoQueueBuilding: Boolean = false,
    voteLabel: String? = null,
    sleepState: SleepTimerState = SleepTimerState(),
    onSleepDurationMs: (Long) -> Unit = {},
    onCancelSleep: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val track = snapshot.track
    val scheme = MaterialTheme.colorScheme
    var showSleepTimer by remember { mutableStateOf(false) }
    val scrim = Brush.verticalGradient(
        colors = listOf(
            scheme.surface.copy(alpha = 0.0f),
            scheme.surface.copy(alpha = 0.55f),
            scheme.surface,
        ),
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top chrome
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCollapse) {
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = "Collapse player",
                        tint = scheme.onSurface,
                    )
                }
                Text(
                    text = "Now playing",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
                // Clock opens floating sleep timer
                IconButton(onClick = { showSleepTimer = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = "Sleep timer",
                        tint = if (sleepState.active) scheme.primary else scheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Artwork hero
            ArtworkHero(
                coverUrl = track?.coverUrl,
                title = track?.title ?: "Nothing playing",
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .aspectRatio(1f),
            )

            Spacer(Modifier.height(28.dp))

            // Metadata — title only
            Text(
                text = track?.title ?: "Select a track",
                style = MaterialTheme.typography.headlineMedium,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(28.dp))

            // Scrubber
            ProgressSection(
                progress = snapshot.progress,
                onSeek = onSeek,
            )

            Spacer(Modifier.height(20.dp))

            // Transport
            TransportRow(
                isPlaying = snapshot.state == PlaybackState.PLAYING,
                isBuffering = snapshot.state == PlaybackState.BUFFERING,
                onPlayPause = onPlayPause,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrevious,
            )

            Spacer(Modifier.height(12.dp))

            // Secondary transport: shuffle / auto-queue radio / repeat
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onShuffle) {
                    Icon(
                        Icons.Outlined.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (snapshot.isShuffle) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onToggleAutoQueue,
                    enabled = track != null,
                ) {
                    if (autoQueueBuilding && autoQueueEnabled) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = scheme.primary,
                        )
                    } else {
                        Icon(
                            imageVector = if (autoQueueEnabled) {
                                Icons.Filled.Radio
                            } else {
                                Icons.Outlined.Radio
                            },
                            contentDescription = if (autoQueueEnabled) {
                                "Tắt auto-queue"
                            } else {
                                "Bật auto-queue"
                            },
                            tint = if (autoQueueEnabled) {
                                scheme.primary
                            } else {
                                scheme.onSurfaceVariant
                            },
                        )
                    }
                }
                IconButton(onClick = onRepeat) {
                    Icon(
                        Icons.Outlined.Repeat,
                        contentDescription = "Repeat",
                        tint = if (snapshot.repeatMode != com.openwave.music.core.domain.RepeatMode.OFF) {
                            scheme.primary
                        } else {
                            scheme.onSurfaceVariant
                        },
                    )
                }
            }

            // Crossfade UI removed until dual-ExoPlayer blend is implemented
            if (sleepState.active) {
                Spacer(Modifier.height(20.dp))
                AssistChip(
                    onClick = { showSleepTimer = true },
                    label = { Text("Sleep ${formatRemain(sleepState.remainingMs)}") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }

            Spacer(Modifier.height(32.dp))
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(24.dp)
                .background(scrim),
        )

        if (showSleepTimer) {
            SleepTimerFloat(
                sleepState = sleepState,
                onSleepDurationMs = { ms ->
                    onSleepDurationMs(ms)
                    showSleepTimer = false
                },
                onCancelSleep = {
                    onCancelSleep()
                    showSleepTimer = false
                },
                onDismiss = { showSleepTimer = false },
            )
        }
    }
}

/**
 * Floating sleep timer — clock dial only after the toolbar clock button is tapped.
 */
@Composable
private fun SleepTimerFloat(
    sleepState: SleepTimerState,
    onSleepDurationMs: (Long) -> Unit,
    onCancelSleep: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val timePickerState = rememberTimePickerState(
        initialHour = 0,
        initialMinute = 15,
        is24Hour = true,
    )
    val selectedMs = (timePickerState.hour * 3_600_000L) + (timePickerState.minute * 60_000L)
    val selectedLabel = formatDurationHm(timePickerState.hour, timePickerState.minute)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = scheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { /* absorb — keep dialog open when tapping card */ },
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Sleep timer",
                            style = MaterialTheme.typography.titleLarge,
                            color = scheme.onSurface,
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Close",
                                tint = scheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (sleepState.active) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = scheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = formatRemain(sleepState.remainingMs),
                                style = MaterialTheme.typography.headlineMedium,
                                color = scheme.onPrimaryContainer,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 14.dp, horizontal = 12.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = selectedLabel,
                        style = MaterialTheme.typography.headlineSmall,
                        color = scheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    TimePicker(
                        state = timePickerState,
                        colors = TimePickerDefaults.colors(
                            clockDialColor = scheme.surfaceVariant,
                            selectorColor = scheme.primary,
                            timeSelectorSelectedContainerColor = scheme.primaryContainer,
                            timeSelectorSelectedContentColor = scheme.onPrimaryContainer,
                            timeSelectorUnselectedContainerColor = scheme.surfaceVariant,
                            timeSelectorUnselectedContentColor = scheme.onSurface,
                        ),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (sleepState.active) {
                            OutlinedButton(
                                onClick = onCancelSleep,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Cancel")
                            }
                        }
                        Button(
                            onClick = { onSleepDurationMs(selectedMs) },
                            enabled = selectedMs > 0L,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                if (sleepState.active) {
                                    "Restart $selectedLabel"
                                } else {
                                    "Start $selectedLabel"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDurationHm(hours: Int, minutes: Int): String = when {
    hours <= 0 && minutes <= 0 -> "0 min"
    hours <= 0 -> "$minutes min"
    minutes <= 0 -> if (hours == 1) "1 hr" else "$hours hr"
    else -> "${hours}h ${minutes}m"
}

@Composable
private fun ArtworkHero(
    coverUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        color = scheme.surfaceVariant,
    ) {
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = "Album art for $title",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                scheme.primaryContainer,
                                scheme.secondaryContainer,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title.take(1).uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.displayLarge,
                    color = scheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ProgressSection(
    progress: PlaybackProgress,
    onSeek: (Long) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var scrubbing by remember { mutableFloatStateOf(-1f) }
    val displayFraction = if (scrubbing >= 0f) scrubbing else progress.fraction

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayFraction,
            onValueChange = { scrubbing = it },
            onValueChangeFinished = {
                if (scrubbing >= 0f && progress.durationMs > 0L) {
                    onSeek((scrubbing * progress.durationMs).toLong())
                }
                scrubbing = -1f
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Seek" },
            colors = SliderDefaults.colors(
                thumbColor = scheme.primary,
                activeTrackColor = scheme.primary,
                inactiveTrackColor = scheme.surfaceVariant,
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatMs(
                    if (scrubbing >= 0f && progress.durationMs > 0L) {
                        (scrubbing * progress.durationMs).toLong()
                    } else {
                        progress.positionMs
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = formatMs(progress.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransportRow(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        label = "playScale",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onSkipPrevious,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                Icons.Filled.SkipPrevious,
                contentDescription = "Previous",
                modifier = Modifier.size(36.dp),
                tint = scheme.onSurface,
            )
        }

        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .size(76.dp)
                .scale(scale),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary,
            ),
            interactionSource = interaction,
        ) {
            AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    (fadeIn() + scaleIn(initialScale = 0.85f)) togetherWith
                        (fadeOut() + scaleOut(targetScale = 0.85f))
                },
                label = "playPauseIcon",
            ) { playing ->
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        IconButton(
            onClick = onSkipNext,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = "Next",
                modifier = Modifier.size(36.dp),
                tint = scheme.onSurface,
            )
        }
    }

    if (isBuffering) {
        // Intentionally minimal — no spinner spam; state lives on the button morph
    }
}

/**
 * Compact bottom mini-player — title + subtitle, favorite, play/pause, seek bar.
 */
@Composable
fun MiniPlayerBar(
    snapshot: PlayerSnapshot,
    onPlayPause: () -> Unit,
    onExpand: () -> Unit,
    onSeek: (Long) -> Unit = {},
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val track = snapshot.track ?: return
    val scheme = MaterialTheme.colorScheme
    val isPlaying = snapshot.state == PlaybackState.PLAYING
    var scrubbing by remember { mutableFloatStateOf(-1f) }
    val progress = snapshot.progress
    val displayFraction = if (scrubbing >= 0f) scrubbing else progress.fraction
    val subtitle = TrackDisplay.subtitle(track)

    Surface(
        onClick = onExpand,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = scheme.secondaryContainer,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (track.coverUrl != null) {
                        AsyncImage(
                            model = track.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = track.title.take(1),
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onPrimaryContainer,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp),
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSecondaryContainer.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (isFavorite) {
                            Icons.Filled.Favorite
                        } else {
                            Icons.Outlined.FavoriteBorder
                        },
                        contentDescription = if (isFavorite) {
                            "Bỏ yêu thích"
                        } else {
                            "Thêm vào Yêu thích"
                        },
                        tint = if (isFavorite) scheme.error else scheme.onSecondaryContainer,
                    )
                }
                FilledIconButton(
                    onClick = onPlayPause,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary,
                    ),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
            }

            // Timeline — live progress + scrub (Surface click still expands above)
            Slider(
                value = displayFraction,
                onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    if (scrubbing >= 0f && progress.durationMs > 0L) {
                        onSeek((scrubbing * progress.durationMs).toLong())
                    }
                    scrubbing = -1f
                },
                enabled = progress.durationMs > 0L,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .height(28.dp)
                    .semantics { contentDescription = "Seek mini player" },
                colors = SliderDefaults.colors(
                    thumbColor = scheme.primary,
                    activeTrackColor = scheme.primary,
                    inactiveTrackColor = scheme.onSecondaryContainer.copy(alpha = 0.2f),
                    disabledActiveTrackColor = scheme.primary.copy(alpha = 0.5f),
                    disabledInactiveTrackColor = scheme.onSecondaryContainer.copy(alpha = 0.12f),
                    disabledThumbColor = scheme.primary.copy(alpha = 0.6f),
                ),
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

private fun formatRemain(ms: Long): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

@Preview(showBackground = true, name = "Now Playing")
@Composable
private fun NowPlayingPreview() {
    OpenWaveTheme(dynamicColor = false) {
        NowPlayingScreen(
            snapshot = PlayerSnapshot(
                track = Track(
                    id = "1",
                    title = "Midnight Circuit",
                    artists = listOf(
                        Artist("a1", "Lumen River", MusicSource.LOCAL),
                    ),
                    durationMs = 214_000L,
                    source = MusicSource.LOCAL,
                ),
                state = PlaybackState.PLAYING,
                progress = PlaybackProgress(positionMs = 72_000L, durationMs = 214_000L),
            ),
            onPlayPause = {},
            onSeek = {},
            onSkipNext = {},
            onSkipPrevious = {},
            onCollapse = {},
        )
    }
}
