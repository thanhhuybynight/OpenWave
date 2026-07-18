package com.openwave.music.core.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.openwave.music.MainActivity
import com.openwave.music.OpenWaveApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground media playback — **screen off / other apps / lock screen**.
 *
 * Speed-oriented ExoPlayer load control:
 * - Smaller min buffer → audio starts sooner after URL resolve
 * - Still enough max buffer for Wi‑Fi handoffs
 *
 * Media3 MediaSessionService already promotes a media notification FGS
 * when playback is active (no GMS).
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()

        // Aggressive for "tap → hear sound" (milliseconds matter after URL is ready)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 1_500,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 750,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also {
                // Keep a bit of queue pre-buffer when items are already in the playlist
                it.pauseAtEndOfMediaItems = false
            }

        player = exo

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, exo)
            .setSessionActivity(sessionActivity)
            .setId(SESSION_ID)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * User swiped app away: keep playing if media is active (hub app behavior).
     * Only stop when idle / ended.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 ||
            p.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
        // else: continue as FGS with media notification
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }

    companion object {
        const val SESSION_ID = "openwave_session"
        const val CHANNEL_ID = OpenWaveApp.PLAYBACK_CHANNEL_ID
    }
}

object MediaItemFactory {
    fun fromUrl(
        mediaId: String,
        title: String,
        artist: String,
        streamUrl: String,
        artworkUri: String? = null,
        httpHeaders: Map<String, String> = emptyMap(),
    ): MediaItem {
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(artworkUri?.let { android.net.Uri.parse(it) })
            .build()

        val requestMetadata = MediaItem.RequestMetadata.Builder()
            .setMediaUri(android.net.Uri.parse(streamUrl))
            .apply {
                if (httpHeaders.isNotEmpty()) {
                    // ExoPlayer reads custom headers via MediaItem extras / datasource factory
                    // in a later phase; URI alone works for public progressive/HLS.
                }
            }
            .build()

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(streamUrl)
            .setMediaMetadata(metadata)
            .setRequestMetadata(requestMetadata)
            .build()
    }
}
