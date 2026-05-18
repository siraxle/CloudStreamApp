package com.example.cloudstreamapp.data.torrent.engine

import android.content.Context
import android.util.Log
import com.example.cloudstreamapp.domain.torrent.TorrentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.PieceFinishedAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton wrapper around a single libtorrent session.
 *
 * Flow for a magnet link:
 *  1. [addMagnet] — calls [SessionManager.fetchMagnet] (blocking I/O thread) to download metadata
 *  2. Starts the actual download with sequential piece priority
 *  3. [PieceFinishedAlert] listener tracks which pieces are ready
 *  4. [TorrentHttpServer] reads pieces on demand via [openInputStream], blocking per piece
 */
@Singleton
class LibtorrentEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "LibtorrentEngine"
        private const val METADATA_TIMEOUT_SEC = 60
        private const val PIECE_TIMEOUT_MS = 30_000L
        private const val SEEK_WINDOW = 20
    }

    val savePath: File = File(context.cacheDir, "torrents").also { it.mkdirs() }

    // Null when the native library is missing (wrong ABI / emulator without x86_64 .so).
    private val session: SessionManager? = try {
        SessionManager()
    } catch (e: LinkageError) {
        Log.e(TAG, "libtorrent4j native library unavailable — torrent streaming disabled: ${e.message}")
        null
    }

    /** False when the native library failed to load. TorrentCloudProvider checks this. */
    val isAvailable: Boolean get() = session != null

    // Keyed by lowercase hex info hash
    private val states = ConcurrentHashMap<String, ActiveTorrent>()

    // Piece waiters: hash → pieceIndex → waiting semaphores
    private val pieceWaiters = ConcurrentHashMap<String, ConcurrentHashMap<Int, MutableList<Semaphore>>>()

    init {
        session?.addListener(object : AlertListener {
            override fun types(): IntArray = intArrayOf(
                AlertType.PIECE_FINISHED.swig(),
                AlertType.TORRENT_ERROR.swig(),
            )

            override fun alert(alert: Alert<*>) {
                when (alert) {
                    is PieceFinishedAlert -> onPieceFinished(alert)
                    is TorrentErrorAlert  -> onTorrentError(alert)
                }
            }
        })
        session?.start()
        if (session != null) Log.i(TAG, "libtorrent session started, savePath=$savePath")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetches torrent metadata via DHT/trackers (blocking, up to [METADATA_TIMEOUT_SEC]s),
     * starts sequential download, and returns the lowercase hex info hash.
     */
    suspend fun addMagnet(magnetUri: String): String = withContext(Dispatchers.IO) {
        val s = session ?: throw IllegalStateException(
            "libtorrent4j native library is not available on this device. " +
            "Torrent streaming requires an arm64-v8a, armeabi-v7a, or x86_64 device."
        )

        val infoHash = extractHexInfoHash(magnetUri)
            ?: throw IllegalArgumentException("No hex info hash found in magnet URI (xt=urn:btih:)")

        if (states.containsKey(infoHash)) return@withContext infoHash

        Log.d(TAG, "Fetching metadata for $infoHash …")
        val metaBytes = s.fetchMagnet(magnetUri, METADATA_TIMEOUT_SEC, savePath)
            ?: throw RuntimeException(
                "Could not fetch metadata from peers within ${METADATA_TIMEOUT_SEC}s. " +
                "Check the magnet link or try again."
            )

        val ti = TorrentInfo.bdecode(metaBytes)
        s.download(ti, savePath)

        // Give libtorrent a moment to register the handle
        val handle = waitForHandle(ti.infoHash(), timeoutMs = 5_000L)
            ?: throw RuntimeException("TorrentHandle not found after starting download")

        // Sequential download + critical piece boost (first 5 + last 5)
        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        val n = ti.numPieces()
        (0 until minOf(5, n)).forEach { handle.piecePriority(it, Priority.TOP_PRIORITY) }
        (maxOf(0, n - 5) until n).forEach { handle.piecePriority(it, Priority.TOP_PRIORITY) }

        states[infoHash] = ActiveTorrent(handle, ti, ConcurrentSkipListSet())
        pieceWaiters[infoHash] = ConcurrentHashMap()

        Log.i(TAG, "Ready: $infoHash — ${ti.numFiles()} files, $n pieces")
        infoHash
    }

    /**
     * Loads a torrent from raw .torrent bytes (no DHT metadata fetch needed),
     * starts sequential download, and returns the lowercase hex info hash.
     */
    suspend fun addTorrentBytes(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val s = session ?: throw IllegalStateException(
            "libtorrent4j native library is not available on this device. " +
            "Torrent streaming requires an arm64-v8a, armeabi-v7a, or x86_64 device."
        )
        val ti = TorrentInfo.bdecode(bytes)
        val infoHash = ti.infoHash().toHex()

        if (states.containsKey(infoHash)) return@withContext infoHash

        s.download(ti, savePath)
        val handle = waitForHandle(ti.infoHash(), timeoutMs = 5_000L)
            ?: throw RuntimeException("TorrentHandle not found after starting download")

        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        val n = ti.numPieces()
        (0 until minOf(5, n)).forEach { handle.piecePriority(it, Priority.TOP_PRIORITY) }
        (maxOf(0, n - 5) until n).forEach { handle.piecePriority(it, Priority.TOP_PRIORITY) }

        states[infoHash] = ActiveTorrent(handle, ti, ConcurrentSkipListSet())
        pieceWaiters[infoHash] = ConcurrentHashMap()

        Log.i(TAG, "Ready (from .torrent file): $infoHash — ${ti.numFiles()} files, $n pieces")
        infoHash
    }

    /** Lists all files in a resolved torrent. */
    fun listFiles(infoHash: String): List<TorrentFile> {
        val state = states[infoHash] ?: return emptyList()
        val fs = state.info.files()
        return (0 until fs.numFiles()).map { i ->
            TorrentFile(
                index        = i,
                name         = File(fs.filePath(i)).name,
                relativePath = fs.filePath(i),
                sizeBytes    = fs.fileSize(i),
            )
        }
    }

    /** Returns the on-disk [File] for [fileIndex] within [infoHash]. */
    fun getFilePath(infoHash: String, fileIndex: Int): File? {
        val state = states[infoHash] ?: return null
        return File(savePath, state.info.files().filePath(fileIndex))
    }

    /** Returns size in bytes of [fileIndex] within [infoHash]. */
    fun fileSize(infoHash: String, fileIndex: Int): Long {
        val state = states[infoHash] ?: return -1L
        return state.info.files().fileSize(fileIndex)
    }

    /**
     * Calculates the piece index that contains [byteOffsetInFile] for [fileIndex].
     * Accounts for the multi-file torrent's global piece layout.
     */
    fun byteToPieceIndex(infoHash: String, fileIndex: Int, byteOffsetInFile: Long): Int {
        val state = states[infoHash] ?: return 0
        val fs = state.info.files()
        var globalOffset = byteOffsetInFile
        for (i in 0 until fileIndex) globalOffset += fs.fileSize(i)
        return (globalOffset / state.info.pieceLength()).toInt()
    }

    /**
     * Blocks until [pieceIndex] is downloaded or [PIECE_TIMEOUT_MS] elapses.
     * Returns true if the piece is ready.
     */
    fun waitForPiece(infoHash: String, pieceIndex: Int): Boolean {
        val state = states[infoHash] ?: return false
        if (pieceIndex in state.downloadedPieces) return true

        val sem = Semaphore(0)
        pieceWaiters[infoHash]
            ?.getOrPut(pieceIndex) { mutableListOf() }
            ?.add(sem)

        // Re-check after registering to avoid a race with onPieceFinished
        return if (pieceIndex in state.downloadedPieces) {
            sem.release()
            true
        } else {
            sem.tryAcquire(PIECE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Re-prioritises pieces around [byteOffset] for low-latency seeking.
     * The next [SEEK_WINDOW] pieces are set to high/normal priority.
     */
    fun seekTo(infoHash: String, fileIndex: Int, byteOffset: Long) {
        val state = states[infoHash] ?: return
        val handle = state.handle
        val info = state.info

        val fs = info.files()
        var globalOffset = byteOffset
        for (i in 0 until fileIndex) globalOffset += fs.fileSize(i)

        val targetPiece = (globalOffset / info.pieceLength()).toInt()
        val total = info.numPieces()

        for (piece in targetPiece until minOf(targetPiece + SEEK_WINDOW, total)) {
            val priority = if (piece - targetPiece < 5) Priority.TOP_PRIORITY else Priority.DEFAULT
            handle.piecePriority(piece, priority)
        }
        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
    }

    /**
     * Opens a blocking [InputStream] that reads [length] bytes starting at [startOffset].
     * The stream suspends per piece until each required piece is downloaded.
     */
    fun openInputStream(
        infoHash: String,
        fileIndex: Int,
        startOffset: Long,
        length: Long,
    ): InputStream {
        val file = getFilePath(infoHash, fileIndex)
            ?: throw IllegalStateException("No file path for $infoHash/$fileIndex")
        return TorrentInputStream(file, this, infoHash, fileIndex, startOffset, length)
    }

    /** Stops downloading a torrent and cleans up all associated state. */
    fun removeTorrent(infoHash: String) {
        val state = states.remove(infoHash) ?: return
        session?.remove(state.handle)
        pieceWaiters.remove(infoHash)
        Log.d(TAG, "Removed torrent $infoHash")
    }

    fun shutdown() {
        if (session?.isRunning == true) {
            session.stop()
            Log.i(TAG, "libtorrent session stopped")
        }
    }

    /** Returns fraction [0.0, 1.0] of pieces already downloaded for [infoHash]. */
    fun getDownloadProgress(infoHash: String): Float {
        val state = states[infoHash] ?: return 0f
        val total = state.info.numPieces().coerceAtLeast(1)
        return state.downloadedPieces.size.toFloat() / total
    }

    /** Emits [getDownloadProgress] every 500 ms until the flow is cancelled. */
    fun downloadProgressFlow(infoHash: String): Flow<Float> = flow {
        while (true) {
            emit(getDownloadProgress(infoHash))
            delay(500)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Emits the download fraction [0.0, 1.0] for a single file every 500 ms.
     * Uses the piece range that belongs exclusively to [fileIndex] in the torrent.
     */
    fun fileDownloadProgressFlow(infoHash: String, fileIndex: Int): Flow<Float> = flow {
        while (true) {
            emit(computeFileProgress(infoHash, fileIndex))
            delay(500)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Sets all pieces belonging to [fileIndex] to TOP_PRIORITY so the torrent
     * client focuses on downloading them before other pieces.
     */
    fun boostFilePriority(infoHash: String, fileIndex: Int) {
        val state = states[infoHash] ?: return
        val (firstPiece, lastPiece) = filePieceRange(state, fileIndex) ?: return
        for (piece in firstPiece..lastPiece) {
            state.handle.piecePriority(piece, Priority.TOP_PRIORITY)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun computeFileProgress(infoHash: String, fileIndex: Int): Float {
        val state = states[infoHash] ?: return 0f
        val (firstPiece, lastPiece) = filePieceRange(state, fileIndex) ?: return 1f
        val total = lastPiece - firstPiece + 1
        val done = state.downloadedPieces.subSet(firstPiece, lastPiece + 1).size
        return done.toFloat() / total
    }

    /** Returns the [firstPiece, lastPiece] piece index range for [fileIndex], or null for empty files. */
    private fun filePieceRange(state: ActiveTorrent, fileIndex: Int): Pair<Int, Int>? {
        val fs = state.info.files()
        val pieceLen = state.info.pieceLength().toLong()
        var globalStart = 0L
        for (i in 0 until fileIndex) globalStart += fs.fileSize(i)
        val fileSize = fs.fileSize(fileIndex)
        if (fileSize <= 0L) return null
        val firstPiece = (globalStart / pieceLen).toInt()
        val lastPiece = ((globalStart + fileSize - 1) / pieceLen).toInt()
        return firstPiece to lastPiece
    }

    // ── Alert handlers ────────────────────────────────────────────────────────

    private fun onPieceFinished(alert: PieceFinishedAlert) {
        val hash = alert.handle().infoHash().toHex()
        val piece = alert.pieceIndex()

        states[hash]?.downloadedPieces?.add(piece)
        pieceWaiters[hash]?.remove(piece)?.forEach { it.release() }
    }

    private fun onTorrentError(alert: TorrentErrorAlert) {
        val hash = alert.handle().infoHash().toHex()
        Log.e(TAG, "Torrent error for $hash: ${alert.message()}")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun waitForHandle(sha1Hash: Sha1Hash, timeoutMs: Long): TorrentHandle? {
        val s = session ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val h = s.find(sha1Hash)
            if (h != null && h.isValid) return h
            Thread.sleep(100)
        }
        return null
    }

    private fun extractHexInfoHash(magnetUri: String): String? =
        Regex("xt=urn:btih:([a-fA-F0-9]{40})", RegexOption.IGNORE_CASE)
            .find(magnetUri)
            ?.groupValues?.get(1)
            ?.lowercase()
}

// ── Internal state ────────────────────────────────────────────────────────────

internal data class ActiveTorrent(
    val handle: TorrentHandle,
    val info: TorrentInfo,
    val downloadedPieces: ConcurrentSkipListSet<Int>,
)

// ── Blocking InputStream over a partially-downloaded file ─────────────────────

/**
 * Reads from a file that libtorrent is writing sequentially.
 * Before each read, blocks until the required piece is downloaded.
 */
private class TorrentInputStream(
    private val file: File,
    private val engine: LibtorrentEngine,
    private val infoHash: String,
    private val fileIndex: Int,
    startOffset: Long,
    private val totalLength: Long,
) : InputStream() {

    private val raf: RandomAccessFile
    private var filePos: Long = startOffset
    private var remaining: Long = totalLength

    init {
        // libtorrent pre-allocates files; wait up to 10 s for the file to appear on disk
        val deadline = System.currentTimeMillis() + 10_000L
        while (!file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        raf = RandomAccessFile(file, "r")
        raf.seek(startOffset)
    }

    override fun read(): Int {
        if (remaining <= 0L) return -1
        ensurePieceReady(filePos)
        return raf.read().also { b -> if (b != -1) { filePos++; remaining-- } }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0L) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        ensurePieceReady(filePos)
        return raf.read(b, off, toRead).also { n -> if (n > 0) { filePos += n; remaining -= n } }
    }

    override fun close() = raf.close()

    private fun ensurePieceReady(byteOffsetInFile: Long) {
        val piece = engine.byteToPieceIndex(infoHash, fileIndex, byteOffsetInFile)
        engine.waitForPiece(infoHash, piece)
    }
}
