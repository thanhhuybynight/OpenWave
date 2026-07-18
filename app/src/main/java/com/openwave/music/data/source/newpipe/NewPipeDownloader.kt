package com.openwave.music.data.source.newpipe

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * OkHttp-backed [Downloader] for NewPipeExtractor (runs on background threads).
 */
class NewPipeDownloader(
    private val http: OkHttpClient = defaultClient(),
) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(
                request.httpMethod(),
                request.dataToSend()?.toRequestBody(null),
            )

        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }
        // Identify as a mobile browser-ish client; avoid empty UA
        if (request.headers()["User-Agent"].isNullOrEmpty()) {
            builder.header("User-Agent", USER_AGENT)
        }

        http.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val latestUrl = resp.request.url.toString()
            if (resp.code == 429) {
                throw ReCaptchaException("reCaptcha / rate limit", latestUrl)
            }
            return Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                body,
                latestUrl,
            )
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
