package com.openwave.music.core.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.openwave.music.core.domain.PlaybackProgress
import com.openwave.music.core.domain.PlaybackState
import com.openwave.music.core.domain.PlayerSnapshot
import com.openwave.music.core.domain.RepeatMode
import com.openwave.music.core.domain.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI-facing façade over Media3 [MediaController].
 * Keeps Compose free of session plumbing.
 */
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    private var currentTrack: Track? = null
    private var queue: List<Track> = emptyList()

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                controller = runCatching { future.get() }.getOrNull()?.also { bind(it) }
            },
            MoreExecutors.directExecutor(),
        )
    }

    fun release() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    fun play(track: Track, streamUrl: String, newQueue: List<Track> = listOf(track)) {
        val c = controller ?: return
        currentTrack = track
        queue = newQueue
        val item = MediaItemFactory.fromUrl(
            mediaId = track.id,
            title = track.title,
            artist = track.artists.joinToString { it.name },
            streamUrl = streamUrl,
            artworkUri = track.coverUrl,
        )
        c.setMediaItem(item)
        c.prepare()
        c.play()
        publish(c)
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun playResume() {
        controller?.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun skipNext() {
        controller?.seekToNextMediaItem()
    }

    fun skipPrevious() {
        controller?.seekToPreviousMediaItem()
    }

    /** Progress ticker for seek bar — collect in a ViewModel scope. */
    fun progressTicks(): Flow<PlaybackProgress> = callbackFlow {
        val c = controller
        if (c == null) {
            close()
            return@callbackFlow
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                trySend(
                    PlaybackProgress(
                        positionMs = player.currentPosition,
                        durationMs = player.duration.coerceAtLeast(0L),
                        bufferedMs = player.bufferedPosition,
                    ),
                )
            }
        }
        c.addListener(listener)
        awaitClose { c.removeListener(listener) }
    }

    private fun bind(c: MediaController) {
        c.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                publish(player)
            }
        })
        publish(c)
    }

    private fun publish(player: Player) {
        _snapshot.update {
            PlayerSnapshot(
                track = currentTrack,
                state = player.toPlaybackState(),
                progress = PlaybackProgress(
                    positionMs = player.currentPosition,
                    durationMs = player.duration.coerceAtLeast(0L),
                    bufferedMs = player.bufferedPosition,
                ),
                queue = queue,
                queueIndex = player.currentMediaItemIndex,
                isShuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode.toRepeatMode(),
            )
        }
    }

    private fun Player.toPlaybackState(): PlaybackState = when {
        playbackState == Player.STATE_BUFFERING -> PlaybackState.BUFFERING
        playbackState == Player.STATE_ENDED -> PlaybackState.ENDED
        playbackState == Player.STATE_IDLE && mediaItemCount == 0 -> PlaybackState.IDLE
        isPlaying -> PlaybackState.PLAYING
        else -> PlaybackState.PAUSED
    }

    private fun Int.toRepeatMode(): RepeatMode = when (this) {
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }
}
