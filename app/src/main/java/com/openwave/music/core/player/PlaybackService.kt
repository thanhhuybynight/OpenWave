package com.openwave.music.core.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.openwave.music.MainActivity
import com.openwave.music.OpenWaveApp
import com.openwave.music.features.audiofx.AudioFxController
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Foreground media playback with OkHttp data source + app-local audio FX.
 * Stream headers are applied per-URL (SoundCloud vs YouTube CDN).
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var okHttp: OkHttpClient
    @Inject lateinit var audioFx: AudioFxController

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        audioFx.start()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 40_000, 750, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val streamHttp = okHttp.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(streamHeadersInterceptor())
            .build()

        val httpFactory = OkHttpDataSource.Factory(streamHttp)
            .setUserAgent(STREAM_USER_AGENT)

        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(audioFx.audioProcessors)
                    .build()
            }
        }

        val exo = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { it.pauseAtEndOfMediaItems = false }

        exo.addListener(
            object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    audioFx.attachSession(audioSessionId)
                }
            },
        )
        // Initial attach (session may already be non-zero)
        audioFx.attachSession(exo.audioSessionId)

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

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 ||
            p.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        audioFx.release()
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
        const val STREAM_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun streamHeadersInterceptor(): Interceptor = Interceptor { chain ->
            val original = chain.request()
            val url = original.url.toString()
            val host = original.url.host
            val registered = StreamRequestHeaders.forUrl(url)
            val defaults = defaultHeadersForHost(host)
            val merged = defaults + registered // registered wins
            val builder = original.newBuilder()
            builder.removeHeader("Origin")
            builder.removeHeader("Referer")
            merged.forEach { (k, v) -> builder.header(k, v) }
            chain.proceed(builder.build())
        }
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
        if (httpHeaders.isNotEmpty()) {
            StreamRequestHeaders.register(streamUrl, httpHeaders)
        }
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(artworkUri?.let { android.net.Uri.parse(it) })
            .build()

        val requestMetadata = MediaItem.RequestMetadata.Builder()
            .setMediaUri(android.net.Uri.parse(streamUrl))
            .build()

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(streamUrl)
            .setMediaMetadata(metadata)
            .setRequestMetadata(requestMetadata)
            .build()
    }
}
