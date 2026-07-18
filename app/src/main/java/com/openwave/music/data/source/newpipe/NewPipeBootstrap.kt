package com.openwave.music.data.source.newpipe

import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewPipeBootstrap @Inject constructor(
    http: OkHttpClient,
) {
    private val ready = AtomicBoolean(false)

    init {
        // Prefer a dedicated client with browser UA for NewPipe
        val downloader = NewPipeDownloader(
            http.newBuilder()
                .addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("User-Agent", NewPipeDownloader.USER_AGENT)
                        .build()
                    chain.proceed(req)
                }
                .build(),
        )
        if (ready.compareAndSet(false, true)) {
            runCatching {
                NewPipe.init(downloader, Localization.DEFAULT, ContentCountry.DEFAULT)
            }
        }
    }

    fun youtubeService() = ServiceList.YouTube

    fun ensureInit() {
        // init in constructor; method for call sites that want a no-op touch
        ready.get()
    }
}
