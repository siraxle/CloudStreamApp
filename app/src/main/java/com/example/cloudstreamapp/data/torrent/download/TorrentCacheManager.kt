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
 */
@Singleton
class TorrentCacheManager @Inject constructor(
    private val engine: LibtorrentEngine,
) {
    companion object {
        private const val TAG = "TorrentCacheManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // key = "$infoHash:$fileIndex"
    private val _progress = MutableStateFlow<Map<String, CacheProgress>>(emptyMap())
    val progress: StateFlow<Map<String, CacheProgress>> = _progress.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    // Tracks which infoHashes have already been scanned on-disk to restore Cached state,
    // so we don't re-scan every time the user navigates folders.
    private val restoredHashes: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

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

            engine.enableFileDownload(infoHash, file.index)
            engine.boostFilePriority(infoHash, file.index)

            _progress.update { it + (key to CacheProgress.Caching(0f)) }

            jobs[key] = scope.launch {
                try {
                    engine.fileDownloadProgressFlow(infoHash, file.index)
                        .onEach { fraction ->
                            _progress.update { it + (key to CacheProgress.Caching(fraction)) }
                        }
                        .first { it >= 1f }
                    _progress.update { it + (key to CacheProgress.Cached) }
                    Log.i(TAG, "Cached: $key (${file.name})")
                } finally {
                    jobs.remove(key)
                }
            }
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
     * Scans the on-disk torrent directory once per infoHash and restores [CacheProgress.Cached]
     * for any files that are already fully downloaded. Called after a torrent is opened so the
     * UI shows correct status without requiring a fresh download.
     */
    fun restoreCacheState(infoHash: String) {
        if (!restoredHashes.add(infoHash)) return
        engine.listFiles(infoHash).filter { it.name.isAudioFile() }.forEach { file ->
            val key = "$infoHash:${file.index}"
            if (_progress.value.containsKey(key)) return@forEach
            val diskFile = engine.getFilePath(infoHash, file.index) ?: return@forEach
            if (diskFile.exists() && diskFile.length() >= file.sizeBytes) {
                _progress.update { it + (key to CacheProgress.Cached) }
                Log.d(TAG, "Restored cached: $key (${file.name})")
            }
        }
    }

    /**
     * Cancels in-progress caching, deletes cached files from disk, and resets libtorrent
     * priorities for all audio files under [folderPath] within [infoHash].
     */
    fun clearFolderCache(infoHash: String, folderPath: String) {
        val prefix = if (folderPath.isEmpty()) "" else "$folderPath/"
        engine.listFiles(infoHash)
            .filter { f -> prefix.isEmpty() || f.relativePath.startsWith(prefix) }
            .filter { f -> f.name.isAudioFile() }
            .forEach { file ->
                val key = "$infoHash:${file.index}"
                jobs.remove(key)?.cancel()
                _progress.update { it - key }
                engine.getFilePath(infoHash, file.index)?.delete()
                engine.resetFilePriority(infoHash, file.index)
            }
        restoredHashes.remove(infoHash)
        Log.i(TAG, "Folder cache cleared: '$folderPath' in $infoHash")
    }

    /**
     * Cancels all caching jobs, clears progress state, and deletes the entire streaming
     * cache from disk. Used by the Settings "Clear torrent cache" action.
     */
    fun clearAllCache() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _progress.value = emptyMap()
        restoredHashes.clear()
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
}
