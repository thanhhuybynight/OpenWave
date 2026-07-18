package com.openwave.music.core.di

import com.openwave.music.features.AiSuggestionEngine
import com.openwave.music.features.ArtistNotificationScheduler
import com.openwave.music.features.BrowseRepository
import com.openwave.music.features.CanvasRepository
import com.openwave.music.features.CrossfadeController
import com.openwave.music.features.LibraryRepository
import com.openwave.music.features.OfflineRepository
import com.openwave.music.features.ReturnYoutubeDislikeClient
import com.openwave.music.features.ScrobbleRepository
import com.openwave.music.features.SleepTimer
import com.openwave.music.features.SponsorBlockClient
import com.openwave.music.features.StreamQualitySelector
import com.openwave.music.features.VideoRepository
import com.openwave.music.features.audiofx.CrossfadeControllerImpl
import com.openwave.music.features.browse.StubBrowseRepository
import com.openwave.music.features.library.InMemoryLibraryRepository
import com.openwave.music.features.offline.InMemoryOfflineRepository
import com.openwave.music.features.quality.DefaultStreamQualitySelector
import com.openwave.music.features.ryd.ReturnYoutubeDislikeClientImpl
import com.openwave.music.features.scrobble.LocalScrobbleRepository
import com.openwave.music.features.sleeptimer.SleepTimerImpl
import com.openwave.music.features.sponsorblock.SponsorBlockClientImpl
import com.openwave.music.features.stubs.StubAiSuggestionEngine
import com.openwave.music.features.stubs.StubArtistNotificationScheduler
import com.openwave.music.features.stubs.StubCanvasRepository
import com.openwave.music.features.stubs.StubVideoRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FeatureModule {

    @Binds @Singleton
    abstract fun browse(impl: StubBrowseRepository): BrowseRepository

    @Binds @Singleton
    abstract fun quality(impl: DefaultStreamQualitySelector): StreamQualitySelector

    @Binds @Singleton
    abstract fun library(impl: InMemoryLibraryRepository): LibraryRepository

    @Binds @Singleton
    abstract fun offline(impl: InMemoryOfflineRepository): OfflineRepository

    @Binds @Singleton
    abstract fun scrobble(impl: LocalScrobbleRepository): ScrobbleRepository

    @Binds @Singleton
    abstract fun sponsorBlock(impl: SponsorBlockClientImpl): SponsorBlockClient

    @Binds @Singleton
    abstract fun ryd(impl: ReturnYoutubeDislikeClientImpl): ReturnYoutubeDislikeClient

    @Binds @Singleton
    abstract fun canvas(impl: StubCanvasRepository): CanvasRepository

    @Binds @Singleton
    abstract fun video(impl: StubVideoRepository): VideoRepository

    @Binds @Singleton
    abstract fun ai(impl: StubAiSuggestionEngine): AiSuggestionEngine

    @Binds @Singleton
    abstract fun crossfade(impl: CrossfadeControllerImpl): CrossfadeController

    @Binds @Singleton
    abstract fun sleepTimer(impl: SleepTimerImpl): SleepTimer

    @Binds @Singleton
    abstract fun artistNotify(impl: StubArtistNotificationScheduler): ArtistNotificationScheduler
}
