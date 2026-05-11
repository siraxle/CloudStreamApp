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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Singleton
class MediaCacheManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private var permMaxBytes = DEFAULT_PERM_MAX_BYTES
    private var released = false

    private val _cacheCleared = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cacheCleared: SharedFlow<Unit> = _cacheCleared.asSharedFlow()

    private val databaseProvider by lazy { StandaloneDatabaseProvider(context) }

    /** Explicitly downloaded tracks. In filesDir — survives OS cache clearing. */
    val permanentCache: SimpleCache by lazy {
        SimpleCache(
            File(context.filesDir, "media_downloads"),
            LeastRecentlyUsedCacheEvictor(permMaxBytes),
            databaseProvider,
        )
    }

    /** Streaming buffer. In cacheDir (OS may clear it); wiped on each app start. */
    val tempCache: SimpleCache by lazy {
        SimpleCache(
            File(context.cacheDir, "media_stream"),
            LeastRecentlyUsedCacheEvictor(DEFAULT_TEMP_MAX_BYTES),
            databaseProvider,
        ).also { clearCacheSpans(it) }
    }

    private fun clearCacheSpans(cache: SimpleCache) {
        try {
            cache.keys.toSet().forEach { key ->
                cache.getCachedSpans(key).toList().forEach { span ->
                    try { cache.removeSpan(span) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    val permUsedBytes: Long
        get() = if (released) 0L else try { permanentCache.cacheSpace } catch (_: Exception) { 0L }

    val tempUsedBytes: Long
        get() = if (released) 0L else try { tempCache.cacheSpace } catch (_: Exception) { 0L }

    val usedBytes: Long get() = permUsedBytes

    fun setMaxCacheBytes(bytes: Long) {
        permMaxBytes = bytes
    }

    fun getCacheStatus(key: String, sizeBytes: Long?): CacheStatus {
        if (released) return CacheStatus.REMOTE
        return try {
            val range = sizeBytes ?: (Long.MAX_VALUE / 2)
            val cachedBytes = permanentCache.getCachedBytes(key, 0, range)
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
            permanentCache.getCachedSpans(key).toList().forEach { span ->
                try { permanentCache.removeSpan(span) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun clearAll() {
        if (released) return
        clearCacheSpans(permanentCache)
        _cacheCleared.tryEmit(Unit)
    }

    fun clearTemp() {
        if (released) return
        clearCacheSpans(tempCache)
    }

    fun release() {
        if (!released) {
            released = true
            try { permanentCache.release() } catch (_: Exception) {}
            try { tempCache.release() } catch (_: Exception) {}
        }
    }

    companion object {
        const val DEFAULT_PERM_MAX_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
        const val DEFAULT_TEMP_MAX_BYTES = 300L * 1024 * 1024       // 300 MB
        const val DEFAULT_MAX_BYTES = DEFAULT_PERM_MAX_BYTES
    }
}
