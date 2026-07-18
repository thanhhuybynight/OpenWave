package com.openwave.music.data.cache

import com.openwave.music.core.domain.StreamInfo
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory stream URL cache — primary latency win after first resolve.
 * Stream URLs (esp. YTM) expire; we honor [StreamInfo.expiresAtEpochMs] and a soft TTL.
 */
@Singleton
class StreamUrlCache @Inject constructor() {

    private data class Entry(
        val info: StreamInfo,
        val cachedAtMs: Long,
        val softTtlMs: Long,
    ) {
        fun isValid(now: Long): Boolean {
            if (now - cachedAtMs > softTtlMs) return false
            val exp = info.expiresAtEpochMs ?: return true
            // Refresh 60s before remote expiry
            return now < exp - 60_000L
        }
    }

    private val map = ConcurrentHashMap<String, Entry>()

    fun get(trackKey: String, now: Long = System.currentTimeMillis()): StreamInfo? {
        val e = map[trackKey] ?: return null
        return if (e.isValid(now)) e.info else {
            map.remove(trackKey, e)
            null
        }
    }

    fun put(trackKey: String, info: StreamInfo, softTtlMs: Long) {
        map[trackKey] = Entry(info, System.currentTimeMillis(), softTtlMs)
    }

    fun remove(trackKey: String) {
        map.remove(trackKey)
    }

    fun clear() = map.clear()

    companion object {
        fun key(source: String, id: String) = "$source::$id"
    }
}
