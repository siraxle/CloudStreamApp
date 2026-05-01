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

    fun setMaxCacheBytes(bytes: Long) {
        maxBytes = bytes
    }

    fun getCacheStatus(key: String, sizeBytes: Long?): CacheStatus {
        if (released) return CacheStatus.REMOTE
        val range = sizeBytes ?: (Long.MAX_VALUE / 2)
        val cachedBytes = simpleCache.getCachedBytes(key, 0, range)
        return when {
            cachedBytes <= 0 -> CacheStatus.REMOTE
            sizeBytes != null && cachedBytes >= sizeBytes -> CacheStatus.CACHED
            else -> CacheStatus.PARTIAL
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
