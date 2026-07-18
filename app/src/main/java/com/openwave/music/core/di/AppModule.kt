package com.openwave.music.core.di

import android.content.Context
import androidx.room.Room
import com.openwave.music.core.domain.AggregatorConfig
import com.openwave.music.core.domain.FastMusicCatalog
import com.openwave.music.core.domain.MusicCatalog
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.MusicSourceClient
import com.openwave.music.data.cache.StreamUrlCache
import com.openwave.music.data.local.DownloadDao
import com.openwave.music.data.local.OpenWaveDatabase
import com.openwave.music.data.local.PlayEventDao
import com.openwave.music.data.local.PlaylistDao
import com.openwave.music.data.local.ScrobbleDao
import com.openwave.music.data.repository.FastMusicCatalogImpl
import com.openwave.music.data.source.DemoSourceClient
import com.openwave.music.data.source.soundcloud.SoundCloudSourceClient
import com.openwave.music.data.source.youtube.YouTubeMusicSourceClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideDb(@ApplicationContext context: Context): OpenWaveDatabase =
        Room.databaseBuilder(context, OpenWaveDatabase::class.java, "openwave.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun scrobbleDao(db: OpenWaveDatabase): ScrobbleDao = db.scrobbleDao()
    @Provides fun playEventDao(db: OpenWaveDatabase): PlayEventDao = db.playEventDao()
    @Provides fun playlistDao(db: OpenWaveDatabase): PlaylistDao = db.playlistDao()
    @Provides fun downloadDao(db: OpenWaveDatabase): DownloadDao = db.downloadDao()

    @Provides
    @Singleton
    fun provideAggregatorConfig(): AggregatorConfig = AggregatorConfig()

    @Provides
    @Singleton
    fun provideSourceClients(
        yt: YouTubeMusicSourceClient,
        sc: SoundCloudSourceClient,
        demo: DemoSourceClient,
    ): Set<@JvmSuppressWildcards MusicSourceClient> = setOf(yt, sc, demo)

    @Provides
    @Singleton
    fun provideFastCatalog(
        clients: Set<@JvmSuppressWildcards MusicSourceClient>,
        cache: StreamUrlCache,
        offline: com.openwave.music.features.OfflineRepository,
        config: AggregatorConfig,
    ): FastMusicCatalog = FastMusicCatalogImpl(clients, cache, offline, config)

    @Provides
    @Singleton
    fun provideCatalog(fast: FastMusicCatalog): MusicCatalog = fast

    @Provides
    @Singleton
    fun provideDefaultSources(): Set<MusicSource> = setOf(
        MusicSource.YOUTUBE_MUSIC,
        MusicSource.SOUNDCLOUD,
    )
}
