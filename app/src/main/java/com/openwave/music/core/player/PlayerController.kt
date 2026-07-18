package com.openwave.music.core.player

import android.content.ComponentName
import android.content.Context
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI-facing façade over Media3 [MediaController].
 * [awaitReady] must succeed before play on cold start.
 */
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val ready = CompletableDeferred<MediaController>()
    private var progressJob: Job? = null

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    private var currentTrack: Track? = null
    private var queue: List<Track> = emptyList()
    private val trackById = linkedMapOf<String, Track>()

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
                val c = runCatching { future.get() }.getOrNull()
                if (c != null) {
                    controller = c
                    bind(c)
                    if (!ready.isCompleted) ready.complete(c)
                } else if (!ready.isCompleted) {
                    ready.completeExceptionally(IllegalStateException("MediaController failed"))
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    /** Wait until MediaController is bound (or timeout). */
    suspend fun awaitReady(timeoutMs: Long = 8_000L): Boolean {
        connect()
        controller?.let { return true }
        return withTimeoutOrNull(timeoutMs) {
            ready.await()
            true
        } == true
    }

    fun release() {
        progressJob?.cancel()
        progressJob = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    fun play(track: Track, streamUrl: String, newQueue: List<Track> = listOf(track)) {
        playResolved(
            tracks = listOf(track to streamUrl),
            startIndex = 0,
            fullQueue = newQueue.ifEmpty { listOf(track) },
        )
    }

    fun playResolved(
        tracks: List<Pair<Track, String>>,
        startIndex: Int = 0,
        fullQueue: List<Track> = tracks.map { it.first },
    ) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        queue = fullQueue
        trackById.clear()
        fullQueue.forEach { trackById[it.id] = it }
        tracks.forEach { trackById[it.first.id] = it.first }

        val items = tracks.map { (t, url) ->
            MediaItemFactory.fromUrl(
                mediaId = t.id,
                title = t.title,
                artist = t.artists.joinToString { it.name },
                streamUrl = url,
                artworkUri = t.coverUrl,
            )
        }
        val idx = startIndex.coerceIn(0, items.lastIndex)
        currentTrack = tracks[idx].first
        c.setMediaItems(items, idx, 0L)
        c.prepare()
        c.play()
        publish(c)
    }

    /** Append resolved items to the end of the current Media3 playlist. */
    fun appendResolved(
        tracks: List<Pair<Track, String>>,
        fullQueueTail: List<Track> = tracks.map { it.first },
    ) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        fullQueueTail.forEach { trackById[it.id] = it }
        tracks.forEach { trackById[it.first.id] = it.first }
        queue = (queue + fullQueueTail).distinctBy { it.id }
        val items = tracks.map { (t, url) ->
            MediaItemFactory.fromUrl(
                mediaId = t.id,
                title = t.title,
                artist = t.artists.joinToString { it.name },
                streamUrl = url,
                artworkUri = t.coverUrl,
            )
        }
        c.addMediaItems(items)
        publish(c)
    }

    fun hasNext(): Boolean = controller?.hasNextMediaItem() == true

    fun mediaItemCount(): Int = controller?.mediaItemCount ?: 0

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
        val c = controller ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem()
        else if (c.repeatMode == Player.REPEAT_MODE_ALL && c.mediaItemCount > 0) {
            c.seekTo(0, 0L)
        }
        syncCurrentFromPlayer(c)
    }

    fun skipPrevious() {
        val c = controller ?: return
        if (c.currentPosition > 3_000L) {
            c.seekTo(0L)
        } else if (c.hasPreviousMediaItem()) {
            c.seekToPreviousMediaItem()
        } else {
            c.seekTo(0L)
        }
        syncCurrentFromPlayer(c)
    }

    fun setShuffle(enabled: Boolean) {
        controller?.shuffleModeEnabled = enabled
        controller?.let { publish(it) }
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        publish(c)
    }

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
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_TIMELINE_CHANGED)
                ) {
                    syncCurrentFromPlayer(player)
                }
                publish(player)
                // Keep UI timeline moving while audio plays — events alone stall.
                if (player.isPlaying) ensureProgressTicker() else stopProgressTicker()
            }
        })
        publish(c)
        if (c.isPlaying) ensureProgressTicker()
    }

    /**
     * Media3 only emits listener events on discrete changes, not every frame of
     * playback position. Tick ~4×/s so mini-player / scrubber stay live.
     */
    private fun ensureProgressTicker() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                val c = controller
                if (c == null || !c.isPlaying) {
                    // Still refresh once when paused so position is accurate after seek
                    c?.let { publishProgressOnly(it) }
                    break
                }
                publishProgressOnly(c)
                delay(250L)
            }
            progressJob = null
        }
    }

    private fun stopProgressTicker() {
        progressJob?.cancel()
        progressJob = null
        controller?.let { publishProgressOnly(it) }
    }

    private fun publishProgressOnly(player: Player) {
        val progress = PlaybackProgress(
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.coerceAtLeast(0L),
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
        )
        // Avoid rewriting the whole snapshot identity when only position moves —
        // still need a new object so StateFlow emits.
        _snapshot.update { snap ->
            if (snap.progress == progress && snap.state == player.toPlaybackState()) snap
            else snap.copy(
                state = player.toPlaybackState(),
                progress = progress,
            )
        }
    }

    private fun syncCurrentFromPlayer(player: Player) {
        val id = player.currentMediaItem?.mediaId
        if (id != null) {
            currentTrack = trackById[id] ?: currentTrack
        }
    }

    private fun publish(player: Player) {
        syncCurrentFromPlayer(player)
        _snapshot.update {
            PlayerSnapshot(
                track = currentTrack,
                state = player.toPlaybackState(),
                progress = PlaybackProgress(
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    durationMs = player.duration.coerceAtLeast(0L),
                    bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
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
