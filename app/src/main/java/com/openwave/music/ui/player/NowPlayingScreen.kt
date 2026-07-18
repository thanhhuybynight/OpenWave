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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.openwave.music.core.domain.Artist
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.PlaybackProgress
import com.openwave.music.core.domain.PlaybackState
import com.openwave.music.core.domain.PlayerSnapshot
import com.openwave.music.core.domain.Track
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
    modifier: Modifier = Modifier,
) {
    val track = snapshot.track
    val scheme = MaterialTheme.colorScheme
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
                IconButton(onClick = { /* queue / menu */ }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "More",
                        tint = scheme.onSurface,
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

            // Metadata
            Text(
                text = track?.title ?: "Select a track",
                style = MaterialTheme.typography.headlineMedium,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = track?.artists?.joinToString { it.name } ?: "OpenWave",
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
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

            Spacer(Modifier.weight(1f))

            // Secondary row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { /* shuffle */ }) {
                    Icon(
                        Icons.Outlined.Shuffle,
                        contentDescription = "Shuffle",
                        tint = scheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { /* repeat */ }) {
                    Icon(
                        Icons.Outlined.Repeat,
                        contentDescription = "Repeat",
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Soft bottom scrim for depth when content is long
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(48.dp)
                .background(scrim),
        )
    }
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
 * Compact bottom mini-player — shared element candidate for expand animation.
 */
@Composable
fun MiniPlayerBar(
    snapshot: PlayerSnapshot,
    onPlayPause: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = snapshot.track ?: return
    val scheme = MaterialTheme.colorScheme
    val isPlaying = snapshot.state == PlaybackState.PLAYING

    Surface(
        onClick = onExpand,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = scheme.secondaryContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artists.joinToString { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSecondaryContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
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
