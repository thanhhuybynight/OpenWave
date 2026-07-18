package com.openwave.music.data.source.youtube

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal YouTube Music InnerTube player client (SimpMusic / WEB_REMIX path).
 *
 * POST https://music.youtube.com/youtubei/v1/player
 * Used for playability + format metadata; actual stream URLs usually come from
 * NewPipe (see [YtmStreamResolver]).
 */
@Singleton
class YtInnerTubePlayer @Inject constructor(
    private val http: OkHttpClient,
) {
    data class PlayerResult(
        val videoId: String,
        val cpn: String,
        val status: String,
        val title: String?,
        val author: String?,
        val lengthSeconds: Long,
        val expiresInSeconds: Long,
        /** itag -> url when player already returns direct URLs */
        val urlsByItag: Map<Int, String>,
        /** itags that only have signatureCipher (need NewPipe deobfuscation) */
        val cipherItags: Set<Int>,
        val rawJson: String,
    )

    fun player(videoId: String): PlayerResult? {
        val cpn = randomCpn()
        val sts = signatureTimestamp()
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", CLIENT_NAME)
                        .put("clientVersion", CLIENT_VERSION)
                        .put("hl", "en")
                        .put("gl", "US")
                        .put("userAgent", USER_AGENT)
                        .put("originalUrl", "https://music.youtube.com/watch?v=$videoId")
                        .put("platform", "DESKTOP"),
                ),
            )
            .put("videoId", videoId)
            .put("cpn", cpn)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put(
                "playbackContext",
                JSONObject().put(
                    "contentPlaybackContext",
                    JSONObject()
                        .put("html5Preference", "HTML5_PREF_WANTS")
                        .put("signatureTimestamp", sts),
                ),
            )
            .toString()

        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(body.toRequestBody(JSON))
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("X-Goog-Api-Format-Version", "1")
            .header("X-YouTube-Client-Name", "67") // WEB_REMIX
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("x-origin", "https://music.youtube.com")
            .build()

        return runCatching {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "player HTTP ${resp.code}")
                    return@use null
                }
                parse(videoId, cpn, text)
            }
        }.onFailure { Log.w(TAG, "player failed: ${it.message}") }
            .getOrNull()
    }

    private fun parse(videoId: String, cpn: String, json: String): PlayerResult {
        val root = JSONObject(json)
        val status = root.optJSONObject("playabilityStatus")
            ?.optString("status")
            .orEmpty()
            .ifBlank { "UNKNOWN" }
        val details = root.optJSONObject("videoDetails")
        val streaming = root.optJSONObject("streamingData")
        val expires = streaming?.optLong("expiresInSeconds") ?: 0L

        val urls = linkedMapOf<Int, String>()
        val ciphers = linkedSetOf<Int>()
        fun absorb(arrName: String) {
            val arr = streaming?.optJSONArray(arrName) ?: return
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val itag = f.optInt("itag", 0)
                if (itag == 0) continue
                val url = f.optString("url")
                if (url.isNotBlank()) {
                    urls[itag] = url
                } else if (f.optString("signatureCipher").isNotBlank() ||
                    f.optString("cipher").isNotBlank()
                ) {
                    ciphers += itag
                }
            }
        }
        absorb("formats")
        absorb("adaptiveFormats")

        return PlayerResult(
            videoId = videoId,
            cpn = cpn,
            status = status,
            title = details?.optString("title"),
            author = details?.optString("author"),
            lengthSeconds = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L,
            expiresInSeconds = expires,
            urlsByItag = urls,
            cipherItags = ciphers,
            rawJson = json,
        )
    }

    companion object {
        private const val TAG = "YtInnerTube"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val CLIENT_NAME = "WEB_REMIX"
        // Keep reasonably current; NewPipe/InnerTube clients rotate often
        private const val CLIENT_VERSION = "1.20250310.01.00"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** SimpMusic-style: days since Unix epoch (approx STS). */
        fun signatureTimestamp(): Int {
            val epoch = Instant.EPOCH.atZone(ZoneOffset.UTC).toLocalDate()
            val today = Instant.now().atZone(ZoneOffset.UTC).toLocalDate()
            return ChronoUnit.DAYS.between(epoch, today).toInt().coerceAtLeast(20073)
        }

        fun randomCpn(): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
            val rnd = ThreadLocalRandom.current()
            return buildString(16) {
                repeat(16) { append(alphabet[rnd.nextInt(alphabet.length)]) }
            }
        }

        /** Preferred audio itags (SimpMusic QUALITY ladder-ish). */
        val AUDIO_ITAG_PRIORITY = listOf(
            774, // high / special
            141, // 256kbps m4a (often Premium)
            251, // 160kbps webm opus
            140, // 128kbps m4a
            250, // 70kbps opus
            249, // 50kbps opus
            139, // 48kbps m4a
        )
    }
}
