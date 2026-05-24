package com.example.cloudstreamapp.data.torrent.download

import android.util.Log
import com.example.cloudstreamapp.core.utils.isAudioFile
import com.example.cloudstreamapp.data.torrent.engine.LibtorrentEngine
import com.example.cloudstreamapp.domain.torrent.CacheProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks caching of torrent files into the libtorrent save directory.
 *
 * Distinct from [TorrentDownloadManager] which copies finished files to the Music folder.
 * This manager enables and monitors piece-by-piece download of specific folders so the
 * user can see Caching → Cached status for each track.
 *
 * Completed-cache state is persisted to [TorrentCachedFileDao] so that on app restart
 * [restoreCacheState] can accurately reflect which files are truly done — instead of
 * relying on disk file size, which is always equal to the full size because libtorrent
 * pre-allocates files on first download.
 */
@Singleton
class TorrentCacheManager @Inject constructor(
    private val engine: LibtorrentEngine,
    private val cachedFileDao: TorrentCachedFileDao,
    private val pendingCacheDao: TorrentPendingCacheDao,
) {
    companion object {
        private const val TAG = "TorrentCacheManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // After libtorrent completes its hash check the verified piece bitfield becomes
        // accurate. Re-run resumePendingCaching so files missed in the initial call
        // (before the check finished, when no fastresume was present) are started.
        scope.launch {
            engine.torrentCheckedFlow.collect { infoHash ->
                resumedHashes.remove(infoHash)
                resumePendingCaching(infoHash)
            }
        }
    }

    // key = "$infoHash:$fileIndex"
    private val _progress = MutableStateFlow<Map<String, CacheProgress>>(emptyMap())
    val progress: StateFlow<Map<String, CacheProgress>> = _progress.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    // Guards restoreCacheState and resumePendingCaching so they each run at most once
    // per infoHash per process lifetime (reset when cache is cleared).
    private val restoredHashes: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val resumedHashes: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * Enables downloading and tracks progress for all audio files under [folderPath]
     * within [infoHash]. [folderPath] must be the full relative path from the torrent
     * root (e.g. "TorrentName/Album") as returned by [LibtorrentEngine.listFiles].
     * Pass an empty string to cache the entire torrent.
     */
    fun cacheFolder(infoHash: String, folderPath: String) {
        val prefix = if (folderPath.isEmpty()) "" else "$folderPath/"
        val files = engine.listFiles(infoHash)
            .filter { f -> prefix.isEmpty() || f.relativePath.startsWith(prefix) }
            .filter { f -> f.name.isAudioFile() }

        if (files.isEmpty()) {
            Log.d(TAG, "cacheFolder: no audio files under '$folderPath' in $infoHash")
            return
        }

        files.forEach { file ->
            val key = "$infoHash:${file.index}"
            if (_progress.value[key] is CacheProgress.Cached) return@forEach
            if (jobs.containsKey(key)) return@forEach

            startCachingJob(infoHash, file.index, file.name, key)
        }
    }

    fun progressFlow(infoHash: String, fileIndex: Int): Flow<CacheProgress?> =
        _progress.map { it["$infoHash:$fileIndex"] }

    fun getProgress(infoHash: String, fileIndex: Int): CacheProgress? =
        _progress.value["$infoHash:$fileIndex"]

    /**
     * Returns the set of folder paths (e.g. "TorrentName/Album") that contain
     * at least one file actively being cached for [infoHash].
     */
    fun activeFolderCachePaths(infoHash: String): Set<String> {
        val cachingIndices = _progress.value
            .filterValues { it is CacheProgress.Caching }
            .keys
            .filter { it.startsWith("$infoHash:") }
            .mapNotNull { it.removePrefix("$infoHash:").toIntOrNull() }
            .toSet()

        if (cachingIndices.isEmpty()) return emptySet()

        return engine.listFiles(infoHash)
            .filter { it.index in cachingIndices }
            .flatMap { file ->
                val segments = file.relativePath.split("/").dropLast(1)
                (1..segments.size).map { n -> segments.take(n).joinToString("/") }
            }
            .toSet()
    }

    /** Total bytes used by the torrent streaming cache on disk. */
    fun totalCacheSizeBytes(): Long = engine.streamingCacheSizeBytes()

    /**
     * Restores [CacheProgress.Cached] from the DB for files that were fully cached in a
     * previous session. Must be called on a non-main thread (uses blocking Room queries).
     *
     * Unlike the previous implementation this does NOT check [File.length] — libtorrent
     * pre-allocates files to their full size the moment a download starts, so a size check
     * would mark every partially-downloaded file as Cached after a restart.
     */
    fun restoreCacheState(infoHash: String) {
        if (!restoredHashes.add(infoHash)) return
        val cachedKeys = cachedFileDao.getKeysForHash(infoHash).toSet()
        if (cachedKeys.isEmpty()) return

        val files = engine.listFiles(infoHash).filter { it.name.isAudioFile() }
        if (files.isEmpty()) {
            // Engine doesn't have this torrent yet (e.g. called before addMagnet/addTorrentBytes
            // from the SavedStateHandle restoration path after a background process kill).
            // Clear the guard so restoration is retried once the torrent is properly opened.
            restoredHashes.remove(infoHash)
            return
        }

        files.forEach { file ->
            val key = "$infoHash:${file.index}"
            if (_progress.value.containsKey(key)) return@forEach
            if (key !in cachedKeys) return@forEach

            val diskFile = engine.getFilePath(infoHash, file.index)
            if (diskFile?.exists() == true) {
                _progress.update { it + (key to CacheProgress.Cached) }
                Log.d(TAG, "Restored cached: $key (${file.name})")
            } else {
                // File recorded as cached in DB but deleted from disk — purge stale entry.
                cachedFileDao.deleteByKeys(listOf(key))
                Log.w(TAG, "Stale cache entry removed: $key (file missing on disk)")
            }
        }
    }

    /**
     * Resumes caching for audio files that were explicitly requested in a prior session
     * (recorded in [TorrentPendingCacheDao]) but not yet finished. Call this after
     * [restoreCacheState] when the user reopens a torrent folder after a restart.
     *
     * Only files present in the pending-cache table are resumed. This prevents boundary
     * pieces — pieces shared between adjacent files in the torrent layout — from
     * accidentally starting caching in neighboring folders.
     *
     * Must be called on a non-main thread (uses blocking Room queries).
     */
    fun resumePendingCaching(infoHash: String) {
        if (!resumedHashes.add(infoHash)) return
        val pendingKeys = pendingCacheDao.getKeysForHash(infoHash).toSet()

        if (pendingKeys.isEmpty()) return

        val cachedKeys = cachedFileDao.getKeysForHash(infoHash).toSet()
        val files = engine.listFiles(infoHash).filter { it.name.isAudioFile() }
        if (files.isEmpty()) {
            resumedHashes.remove(infoHash)
            return
        }

        files.forEach { file ->
            val key = "$infoHash:${file.index}"
            if (key !in pendingKeys) return@forEach
            if (_progress.value[key] is CacheProgress.Cached) return@forEach
            if (jobs.containsKey(key)) return@forEach
            if (key in cachedKeys) return@forEach

            Log.d(TAG, "Resuming partial cache: $key (${file.name})")
            startCachingJob(infoHash, file.index, file.name, key)
        }
    }

    /**
     * Cancels in-progress caching, deletes cached files from disk, and resets libtorrent
     * priorities for all audio files under [folderPath] within [infoHash].
     * Must be called on a non-main thread (uses blocking Room queries).
     */
    fun clearFolderCache(infoHash: String, folderPath: String) {
        val prefix = if (folderPath.isEmpty()) "" else "$folderPath/"
        val keysToDelete = mutableListOf<String>()

        engine.listFiles(infoHash)
            .filter { f -> prefix.isEmpty() || f.relativePath.startsWith(prefix) }
            .filter { f -> f.name.isAudioFile() }
            .forEach { file ->
                val key = "$infoHash:${file.index}"
                keysToDelete += key
                jobs.remove(key)?.cancel()
                _progress.update { it - key }
                engine.getFilePath(infoHash, file.index)?.delete()
                engine.resetFilePriority(infoHash, file.index)
            }

        if (keysToDelete.isNotEmpty()) {
            cachedFileDao.deleteByKeys(keysToDelete)
            pendingCacheDao.deleteByKeys(keysToDelete)
        }
        restoredHashes.remove(infoHash)
        resumedHashes.remove(infoHash)
        Log.i(TAG, "Folder cache cleared: '$folderPath' in $infoHash")
    }

    /**
     * Cancels all caching jobs, clears progress state, and deletes the entire streaming
     * cache from disk. Used by the Settings "Clear torrent cache" action.
     * Must be called on a non-main thread (uses blocking Room queries).
     */
    fun clearAllCache() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _progress.value = emptyMap()
        restoredHashes.clear()
        resumedHashes.clear()
        cachedFileDao.deleteAll()
        pendingCacheDao.deleteAll()
        engine.clearStreamingCache()
        Log.i(TAG, "All streaming cache cleared")
    }

    /**
     * Returns the set of folder paths where every audio file is fully cached.
     */
    fun cachedFolderPaths(infoHash: String): Set<String> {
        val cachedIndices = _progress.value
            .filterValues { it is CacheProgress.Cached }
            .keys
            .filter { it.startsWith("$infoHash:") }
            .mapNotNull { it.removePrefix("$infoHash:").toIntOrNull() }
            .toSet()

        val audioFiles = engine.listFiles(infoHash).filter { it.name.isAudioFile() }
        if (audioFiles.isEmpty()) return emptySet()

        val byFolder = audioFiles.groupBy { it.relativePath.substringBeforeLast("/", "") }

        return byFolder
            .filter { (_, files) -> files.all { it.index in cachedIndices } }
            .keys
            .toSet()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun startCachingJob(infoHash: String, fileIndex: Int, fileName: String, key: String) {
        engine.enableFileDownload(infoHash, fileIndex)
        engine.boostFilePriority(infoHash, fileIndex)
        _progress.update { it + (key to CacheProgress.Caching(0f)) }
        pendingCacheDao.insert(TorrentPendingCacheEntity(key, infoHash, fileIndex))

        jobs[key] = scope.launch {
            try {
                engine.fileDownloadProgressFlow(infoHash, fileIndex)
                    .onEach { fraction ->
                        _progress.update { it + (key to CacheProgress.Caching(fraction)) }
                    }
                    .first { it >= 1f }
                _progress.update { it + (key to CacheProgress.Cached) }
                cachedFileDao.insert(TorrentCachedFileEntity(key, infoHash, fileIndex))
                Log.i(TAG, "Cached: $key ($fileName)")
            } finally {
                jobs.remove(key)
                pendingCacheDao.deleteByKey(key)
            }
        }
    }
}
