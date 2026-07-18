package com.openwave.music.core.di

import com.openwave.music.core.domain.AggregatorConfig
import com.openwave.music.core.domain.FastMusicCatalog
import com.openwave.music.core.domain.MusicCatalog
import com.openwave.music.core.domain.MusicSource
import com.openwave.music.core.domain.MusicSourceClient
import com.openwave.music.data.cache.StreamUrlCache
import com.openwave.music.data.repository.FastMusicCatalogImpl
import com.openwave.music.data.source.DemoSourceClient
import com.openwave.music.data.source.SoundCloudSourceClient
import com.openwave.music.data.source.YouTubeMusicSourceClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
        // Short connect timeout: fail fast, try next source
        return OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(logging)
            .build()
    }

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
        config: AggregatorConfig,
    ): FastMusicCatalog = FastMusicCatalogImpl(clients, cache, config)

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
