package com.example.cloudstreamapp.core.cache

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.example.cloudstreamapp.domain.model.CacheStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaCacheManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private var maxBytes = DEFAULT_MAX_BYTES
    private var released = false

    val simpleCache: SimpleCache by lazy {
        SimpleCache(
            File(context.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(maxBytes),
            StandaloneDatabaseProvider(context),
        )
    }

    val usedBytes: Long get() = if (released) 0L else try { simpleCache.cacheSpace } catch (_: Exception) { 0L }

    fun setMaxCacheBytes(bytes: Long) {
        maxBytes = bytes
    }

    fun getCacheStatus(key: String, sizeBytes: Long?): CacheStatus {
        if (released) return CacheStatus.REMOTE
        return try {
            val range = sizeBytes ?: (Long.MAX_VALUE / 2)
            val cachedBytes = simpleCache.getCachedBytes(key, 0, range)
            when {
                cachedBytes <= 0 -> CacheStatus.REMOTE
                sizeBytes != null && cachedBytes >= sizeBytes -> CacheStatus.CACHED
                else -> CacheStatus.PARTIAL
            }
        } catch (_: Exception) {
            CacheStatus.REMOTE
        }
    }

    fun removeCachedFile(key: String) {
        if (released) return
        try {
            // toList() prevents ConcurrentModificationException while removing spans
            simpleCache.getCachedSpans(key).toList().forEach { span ->
                try { simpleCache.removeSpan(span) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /**
     * Removes all cached spans from disk without closing the cache.
     * Safe to call at any time; the cache remains usable afterward.
     */
    fun clearAll() {
        if (released) return
        try {
            simpleCache.keys.toSet().forEach { key ->
                simpleCache.getCachedSpans(key).toList().forEach { span ->
                    try { simpleCache.removeSpan(span) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Permanently releases the cache. Only call on app shutdown —
     * after this, getCacheStatus() always returns REMOTE for the session.
     */
    fun release() {
        if (!released) {
            simpleCache.release()
            released = true
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
    }
}
