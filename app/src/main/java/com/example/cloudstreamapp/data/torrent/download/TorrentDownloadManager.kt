package com.example.cloudstreamapp.data.torrent.download

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.cloudstreamapp.core.database.AppDatabase
import com.example.cloudstreamapp.core.utils.isAudioFile
import com.example.cloudstreamapp.data.torrent.engine.LibtorrentEngine
import com.example.cloudstreamapp.domain.torrent.DownloadProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorrentDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: LibtorrentEngine,
    private val db: AppDatabase,
) {
    companion object {
        private const val TAG = "TorrentDownloadManager"
    }

    // Lives for the process lifetime (Singleton). Downloads survive ViewModel recreation.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val downloadDir: File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "CloudStream"
    ).also { it.mkdirs() }

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    init {
        // Populate Done entries from Room on startup; keeps in sync when DB changes.
        scope.launch {
            db.torrentDownloadDao().getAll().collect { entities ->
                val doneIds = entities.associateBy { it.id }
                _progress.update { current ->
                    val updated = current.toMutableMap()
                    entities.forEach { e ->
                        val cur = updated[e.id]
                        if (cur == null || cur is DownloadProgress.Failed) {
                            updated[e.id] = DownloadProgress.Done(e.localPath)
                        }
                    }
                    // Remove Done entries that were deleted from DB
                    val stale = updated.keys.filter { k ->
                        updated[k] is DownloadProgress.Done && k !in doneIds
                    }
                    stale.forEach { updated.remove(it) }
                    updated
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun progressFlow(infoHash: String, fileIndex: Int): Flow<DownloadProgress?> =
        _progress.map { it["$infoHash:$fileIndex"] }

    fun getProgress(infoHash: String, fileIndex: Int): DownloadProgress? =
        _progress.value["$infoHash:$fileIndex"]

    /**
     * Starts downloading [fileIndex] in [infoHash] to permanent Music storage.
     * No-op if already downloaded or a download is already in progress.
     */
    fun downloadFile(
        infoHash: String,
        fileIndex: Int,
        fileName: String,
        sizeBytes: Long,
        torrentName: String,
        folderPath: String = "",
    ) {
        val key = "$infoHash:$fileIndex"
        when (_progress.value[key]) {
            is DownloadProgress.Done,
            is DownloadProgress.Queued,
            is DownloadProgress.Downloading -> return
            else -> {}
        }

        setProgress(key, DownloadProgress.Queued)

        jobs[key] = scope.launch {
            try {
                engine.boostFilePriority(infoHash, fileIndex)

                // Wait until all pieces for this file are downloaded
                engine.fileDownloadProgressFlow(infoHash, fileIndex)
                    .onEach { fraction -> setProgress(key, DownloadProgress.Downloading(fraction)) }
                    .first { it >= 1f }

                val srcFile = engine.getFilePath(infoHash, fileIndex)
                    ?: error("File not found in engine: $key")

                // libtorrent pre-allocates sparse files; wait up to 10 s for real bytes
                val deadline = System.currentTimeMillis() + 10_000L
                while (!srcFile.exists() && System.currentTimeMillis() < deadline) delay(100)
                check(srcFile.exists()) { "Downloaded file missing at ${srcFile.path}" }

                val destFile = resolveDestFile(torrentName, folderPath, fileName)
                srcFile.copyTo(destFile, overwrite = true)

                db.torrentDownloadDao().insert(
                    TorrentDownloadEntity(
                        id = key,
                        infoHash = infoHash,
                        fileIndex = fileIndex,
                        localPath = destFile.absolutePath,
                        fileName = fileName,
                        sizeBytes = sizeBytes,
                        torrentName = torrentName,
                        folderPath = folderPath,
                    )
                )
                setProgress(key, DownloadProgress.Done(destFile.absolutePath))
                Log.i(TAG, "Saved: $fileName → ${destFile.path}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for $key", e)
                setProgress(key, DownloadProgress.Failed(e.message ?: "Download failed"))
            } finally {
                jobs.remove(key)
            }
        }
    }

    /**
     * Downloads all audio files under [folderPath] within [infoHash].
     * [folderPath] = "" downloads the entire torrent.
     */
    fun downloadFolder(infoHash: String, folderPath: String, torrentName: String) {
        val prefix = if (folderPath.isEmpty()) "" else "$folderPath/"
        engine.listFiles(infoHash)
            .filter { f -> folderPath.isEmpty() || f.relativePath.startsWith(prefix) }
            .filter { f -> f.name.isAudioFile() }
            .forEach { file ->
                // relativePath = "TorrentRoot/Sub/File.mp3" → fileFolderPath = "Sub"
                val parentDir = file.relativePath.substringBeforeLast("/", "")
                val fileFolderPath = parentDir.substringAfter("/", "")
                downloadFile(
                    infoHash = infoHash,
                    fileIndex = file.index,
                    fileName = file.name,
                    sizeBytes = file.sizeBytes,
                    torrentName = torrentName,
                    folderPath = fileFolderPath,
                )
            }
    }

    /**
     * Cancels all active downloads for audio files under [folderPath] within [infoHash].
     * Pass [folderPath] = "" to cancel every active download in the torrent.
     */
    fun cancelFolder(infoHash: String, folderPath: String) {
        val prefix = if (folderPath.isEmpty()) "" else "$folderPath/"
        engine.listFiles(infoHash)
            .filter { f -> folderPath.isEmpty() || f.relativePath.startsWith(prefix) }
            .filter { f -> f.name.isAudioFile() }
            .forEach { file -> cancelDownload(infoHash, file.index) }
    }

    /**
     * Returns the set of folder paths (e.g. "Album", "Album/Disc1") that contain
     * at least one Queued or Downloading file for [infoHash]. Used to show cancel
     * buttons on folder items in the browser.
     */
    fun activeFolderPaths(infoHash: String): Set<String> {
        val activeIndices = _progress.value
            .filterValues { it is DownloadProgress.Queued || it is DownloadProgress.Downloading }
            .keys
            .filter { it.startsWith("$infoHash:") }
            .mapNotNull { it.removePrefix("$infoHash:").toIntOrNull() }
            .toSet()

        if (activeIndices.isEmpty()) return emptySet()

        return engine.listFiles(infoHash)
            .filter { it.index in activeIndices }
            .flatMap { file ->
                // Build all ancestor folder paths for this file.
                // file.relativePath = "TorrentRoot/SubFolder/Track.mp3"
                // → folder paths: "TorrentRoot", "TorrentRoot/SubFolder"
                val segments = file.relativePath.split("/").dropLast(1)
                (1..segments.size).map { n -> segments.take(n).joinToString("/") }
            }
            .toSet()
    }

    /** Cancels an active download and clears its progress entry. */
    fun cancelDownload(infoHash: String, fileIndex: Int) {
        val key = "$infoHash:$fileIndex"
        jobs.remove(key)?.cancel()
        _progress.update { it - key }
    }

    /** Cancels, deletes the local copy, and removes the DB record. */
    suspend fun deleteDownload(infoHash: String, fileIndex: Int) {
        val key = "$infoHash:$fileIndex"
        cancelDownload(infoHash, fileIndex)
        val entity = db.torrentDownloadDao().findById(key)
        if (entity != null) {
            File(entity.localPath).delete()
            db.torrentDownloadDao().deleteById(key)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun setProgress(key: String, state: DownloadProgress) {
        _progress.update { it + (key to state) }
    }

    private fun resolveDestFile(torrentName: String, folderPath: String, fileName: String): File {
        val torrentDir = File(downloadDir, sanitize(torrentName))
        val dir = if (folderPath.isEmpty()) torrentDir
                  else folderPath.split("/").fold(torrentDir) { acc, seg -> File(acc, sanitize(seg)) }
        dir.mkdirs()
        return File(dir, sanitize(fileName))
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").trimEnd('.', ' ').take(200)
}
