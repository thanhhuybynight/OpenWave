package com.openwave.music.data.source

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolve ISO country code from the device's public IP (for regional charts).
 * Falls back to device locale when geo lookup fails.
 */
@Singleton
class IpRegionClient @Inject constructor(
    private val http: OkHttpClient,
) {
    data class Region(
        /** ISO 3166-1 alpha-2, lowercase (e.g. "vn", "us"). */
        val countryCode: String,
        val countryName: String? = null,
        val fromIp: Boolean,
    )

    private val cache = AtomicReference<Region?>(null)
    private var cacheAt = 0L

    suspend fun region(): Region = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache.get()?.takeIf { now - cacheAt < CACHE_TTL_MS }?.let { return@withContext it }

        val fromIp = runCatching { fetchFromIp() }.getOrNull()
        val resolved = fromIp ?: localeFallback()
        cache.set(resolved)
        cacheAt = now
        Log.i(TAG, "region=${resolved.countryCode} ip=${resolved.fromIp} name=${resolved.countryName}")
        resolved
    }

    private fun fetchFromIp(): Region? {
        // HTTPS, no API key. Prefer ipapi.co; fall back to country.is
        fetchIpApiCo()?.let { return it }
        return fetchCountryIs()
    }

    private fun fetchIpApiCo(): Region? {
        val req = Request.Builder()
            .url("https://ipapi.co/json/")
            .header("User-Agent", "OpenWave/0.1")
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful || body.isBlank()) return@use null
            val o = JSONObject(body)
            if (o.has("error")) return@use null
            val code = o.optString("country_code")
                .ifBlank { o.optString("country") }
                .trim()
                .lowercase(Locale.US)
                .takeIf { it.length == 2 }
                ?: return@use null
            Region(
                countryCode = code,
                countryName = o.optString("country_name").takeIf { it.isNotBlank() },
                fromIp = true,
            )
        }
    }

    private fun fetchCountryIs(): Region? {
        val req = Request.Builder()
            .url("https://api.country.is/")
            .header("User-Agent", "OpenWave/0.1")
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful || body.isBlank()) return@use null
            val o = JSONObject(body)
            val code = o.optString("country")
                .trim()
                .lowercase(Locale.US)
                .takeIf { it.length == 2 }
                ?: return@use null
            Region(countryCode = code, countryName = null, fromIp = true)
        }
    }

    private fun localeFallback(): Region {
        val code = Locale.getDefault().country
            .lowercase(Locale.US)
            .takeIf { it.length == 2 }
            ?: "us"
        return Region(
            countryCode = code,
            countryName = Locale.getDefault().displayCountry.takeIf { it.isNotBlank() },
            fromIp = false,
        )
    }

    companion object {
        private const val TAG = "IpRegion"
        private const val CACHE_TTL_MS = 6 * 60 * 60_000L // 6h
    }
}
