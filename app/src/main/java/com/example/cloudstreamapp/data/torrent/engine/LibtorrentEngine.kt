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
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.PieceFinishedAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.SaveResumeDataFailedAlert
import org.libtorrent4j.alerts.TorrentCheckedAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.swig.libtorrent as LibtorrentSwig
import org.libtorrent4j.swig.settings_pack as SwigSettingsPack
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Singleton wrapper around a single libtorrent session.
 *
 * Flow for a magnet link:
 *  1. [addMagnet] — if metadata was previously saved, restores from disk (offline-capable).
 *     Otherwise calls [SessionManager.fetchMagnet] (blocking I/O thread) to download metadata
 *     via DHT, then persists it for future offline use.
 *  2. If a .fastresume file exists, the torrent is restored with full piece state — no
 *     re-hashing required. This is also the foundation for seeding.
 *  3. Starts sequential download with piece priorities.
 *  4. [PieceFinishedAlert] listener tracks which pieces are ready.
 *  5. [TorrentHttpServer] reads pieces on demand via [openInputStream], blocking per piece.
 *  6. Resume data is saved every [PIECES_PER_RESUME_SAVE] pieces and on session shutdown.
 */
@Singleton
class LibtorrentEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "LibtorrentEngine"
        private const val METADATA_TIMEOUT_SEC = 120
        private const val PIECE_TIMEOUT_MS = 30_000L
        private const val SEEK_WINDOW = 20
        private const val PIECES_PER_RESUME_SAVE = 50
    }

    val savePath: File = File(context.cacheDir, "torrents").also { it.mkdirs() }

    /**
     * Stores .torrent metadata bytes and .fastresume data across app restarts.
     * Lives in filesDir (not cacheDir) so it survives low-storage cache eviction.
     */
    private val metadataDir: File = File(context.filesDir, "torrent_metadata").also { it.mkdirs() }

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

    // Emits infoHash whenever libtorrent finishes hash-checking a torrent.
    private val _torrentChecked = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val torrentCheckedFlow: SharedFlow<String> = _torrentChecked.asSharedFlow()

    init {
        session?.addListener(object : AlertListener {
            override fun types(): IntArray = intArrayOf(
                AlertType.PIECE_FINISHED.swig(),
                AlertType.TORRENT_CHECKED.swig(),
                AlertType.TORRENT_ERROR.swig(),
                AlertType.SAVE_RESUME_DATA.swig(),
                AlertType.SAVE_RESUME_DATA_FAILED.swig(),
            )

            override fun alert(alert: Alert<*>) {
                when (alert) {
                    is PieceFinishedAlert       -> onPieceFinished(alert)
                    is TorrentCheckedAlert      -> onTorrentChecked(alert)
                    is TorrentErrorAlert        -> onTorrentError(alert)
                    is SaveResumeDataAlert      -> onSaveResumeData(alert)
                    is SaveResumeDataFailedAlert -> {
                        val hash = alert.handle().infoHash().toHex()
                        Log.w(TAG, "Resume data save failed for $hash: ${alert.message()}")
                    }
                }
            }
        })
        session?.start(SessionParams(buildSessionSettings()))
        if (session != null) Log.i(TAG, "libtorrent session started, savePath=$savePath")
    }

    // ── Session setup ─────────────────────────────────────────────────────────

    /**
     * Forces RC4 peer-protocol encryption and listens on port 443 in addition to 6881.
     * This allows connections to survive on mobile networks where:
     * - UDP is blocked by the ISP (only TCP trackers will work, but peers can still connect)
     * - BitTorrent DPI filters are active (RC4 encryption makes traffic unrecognisable)
     * - Port 6881 is blocked (port 443 is almost never blocked even on restrictive networks)
     */
    private fun buildSessionSettings(): SettingsPack = SettingsPack().apply {
        // Force encrypted connections — evades BitTorrent DPI on mobile ISPs
        setInteger(
            SwigSettingsPack.int_types.out_enc_policy.swigValue(),
            SwigSettingsPack.enc_policy.pe_forced.swigValue()
        )
        setInteger(
            SwigSettingsPack.int_types.in_enc_policy.swigValue(),
            SwigSettingsPack.enc_policy.pe_forced.swigValue()
        )
        // RC4 stream cipher — stronger obfuscation than plaintext XOR header
        setInteger(
            SwigSettingsPack.int_types.allowed_enc_level.swigValue(),
            SwigSettingsPack.enc_level.pe_rc4.swigValue()
        )
        // Add port 443 as a listen interface — rarely blocked even on restrictive mobile networks
        setString(
            SwigSettingsPack.string_types.listen_interfaces.swigValue(),
            "0.0.0.0:6881,[::]:6881,0.0.0.0:443,[::]:443"
        )
        // Request more peers per tracker announce — speeds up discovery when DHT/UDP is blocked
        setInteger(SwigSettingsPack.int_types.num_want.swigValue(), 400)
        // More outgoing connection attempts per second (default 30)
        setInteger(SwigSettingsPack.int_types.connection_speed.swigValue(), 50)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Opens a torrent by magnet URI.
     *
     * If a .torrent metadata file was saved from a previous session, the torrent is
     * restored from disk without any DHT/network calls — enabling offline playback of
     * previously cached content. If a matching .fastresume file also exists, piece state
     * is restored so libtorrent skips re-hashing files.
     *
     * Falls back to a DHT metadata fetch when no saved metadata exists, then persists
     * the result for future offline use.
     */
    suspend fun addMagnet(magnetUri: String): String = withContext(Dispatchers.IO) {
        val s = session ?: throw IllegalStateException(
            "libtorrent4j native library is not available on this device. " +
            "Torrent streaming requires an arm64-v8a, armeabi-v7a, or x86_64 device."
        )

        val infoHash = extractHexInfoHash(magnetUri)
            ?: throw IllegalArgumentException("No hex info hash found in magnet URI (xt=urn:btih:)")

        if (states.containsKey(infoHash)) return@withContext infoHash

        val torrentFile = File(metadataDir, "$infoHash.torrent")
        val resumeFile  = File(metadataDir, "$infoHash.fastresume")

        val ti: TorrentInfo
        val fromFastresume: Boolean

        if (torrentFile.exists()) {
            // ── Offline path: restore from persisted metadata ──────────────────
            Log.d(TAG, "Restoring $infoHash from saved metadata (no DHT needed)")
            ti = TorrentInfo.bdecode(torrentFile.readBytes())

            if (resumeFile.exists()) {
                Log.d(TAG, "Using fastresume data for $infoHash")
                s.download(ti, savePath, resumeFile, null, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                fromFastresume = true
            } else {
                s.download(ti, savePath)
                fromFastresume = false
            }
        } else {
            // ── Online path: DHT metadata fetch ────────────────────────────────
            Log.d(TAG, "Fetching metadata for $infoHash via DHT…")
            val metaBytes = s.fetchMagnet(magnetUri, METADATA_TIMEOUT_SEC, savePath)
                ?: throw RuntimeException(
                    "Could not fetch metadata from peers within ${METADATA_TIMEOUT_SEC}s. " +
                    "Check the magnet link or try again."
                )

            ti = TorrentInfo.bdecode(metaBytes)
            torrentFile.writeBytes(metaBytes)
            Log.d(TAG, "Saved .torrent metadata for future offline use ($infoHash)")

            s.download(ti, savePath)
            fromFastresume = false
        }

        // Give libtorrent a moment to register the handle
        val handle = waitForHandle(ti.infoHash(), timeoutMs = 5_000L)
            ?: throw RuntimeException("TorrentHandle not found after starting download")

        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)

        val activeTorrent = ActiveTorrent(handle, ti, ConcurrentSkipListSet())
        states[infoHash]     = activeTorrent
        pieceWaiters[infoHash] = ConcurrentHashMap()

        if (fromFastresume) {
            // Populate our in-memory piece set from libtorrent's status so that
            // waitForPiece() immediately recognises already-downloaded pieces.
            populateDownloadedPieces(infoHash, handle, ti, activeTorrent)
        } else {
            // Fresh add: disable all files so only explicitly requested pieces download.
            for (i in 0 until ti.numFiles()) handle.filePriority(i, Priority.IGNORE)
        }

        Log.i(TAG, "Ready: $infoHash — ${ti.numFiles()} files (fastresume=$fromFastresume)")
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

        // Persist metadata so this torrent can also be opened offline via magnet in future
        val torrentFile = File(metadataDir, "$infoHash.torrent")
        if (!torrentFile.exists()) torrentFile.writeBytes(bytes)

        val resumeFile = File(metadataDir, "$infoHash.fastresume")
        if (resumeFile.exists()) {
            s.download(ti, savePath, resumeFile, null, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        } else {
            s.download(ti, savePath)
        }

        val handle = waitForHandle(ti.infoHash(), timeoutMs = 5_000L)
            ?: throw RuntimeException("TorrentHandle not found after starting download")

        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)

        val activeTorrent = ActiveTorrent(handle, ti, ConcurrentSkipListSet())
        states[infoHash]      = activeTorrent
        pieceWaiters[infoHash] = ConcurrentHashMap()

        if (resumeFile.exists()) {
            populateDownloadedPieces(infoHash, handle, ti, activeTorrent)
        } else {
            for (i in 0 until ti.numFiles()) handle.filePriority(i, Priority.IGNORE)
        }

        Log.i(TAG, "Ready (from .torrent file): $infoHash — ${ti.numFiles()} files")
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
     *
     * For torrents restored from fastresume the in-memory [ActiveTorrent.downloadedPieces]
     * set is pre-populated at startup, but as a safety net we also query libtorrent's
     * live status so that pieces already on disk are never waited for unnecessarily.
     */
    fun waitForPiece(infoHash: String, pieceIndex: Int): Boolean {
        val state = states[infoHash] ?: return false
        if (pieceIndex in state.downloadedPieces) return true

        // Safety net: piece may already be on disk (fastresume restore or re-added torrent)
        if (isPieceDownloadedInSession(state, pieceIndex)) {
            state.downloadedPieces.add(pieceIndex)
            return true
        }

        val sem = Semaphore(0)
        pieceWaiters[infoHash]
            ?.getOrPut(pieceIndex) { mutableListOf() }
            ?.add(sem)

        // Re-check after registering to avoid a race with onPieceFinished / onTorrentChecked
        if (pieceIndex in state.downloadedPieces) {
            sem.release()
            return true
        }

        // Poll the live bitfield every 500 ms so that a piece verified by the hash check
        // (but not yet signalled via onPieceFinished) unblocks within 500 ms rather than
        // waiting the full PIECE_TIMEOUT_MS.
        val deadline = System.currentTimeMillis() + PIECE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (sem.tryAcquire(500, TimeUnit.MILLISECONDS)) return true
            if (isPieceDownloadedInSession(state, pieceIndex)) {
                state.downloadedPieces.add(pieceIndex)
                return true
            }
        }
        return false
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

    /** Returns total bytes used by all files in the torrent streaming cache directory. */
    fun streamingCacheSizeBytes(): Long =
        savePath.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Removes all active torrents and deletes everything in [savePath] and [metadataDir]. */
    fun clearStreamingCache() {
        states.keys.toList().forEach { hash ->
            val state = states.remove(hash) ?: return@forEach
            session?.remove(state.handle)
            pieceWaiters.remove(hash)
        }
        savePath.deleteRecursively()
        savePath.mkdirs()
        metadataDir.deleteRecursively()
        metadataDir.mkdirs()
        Log.i(TAG, "Streaming cache and metadata cleared")
    }

    /** Sets [fileIndex] priority back to IGNORE so libtorrent won't re-download it. */
    fun resetFilePriority(infoHash: String, fileIndex: Int) {
        val state = states[infoHash] ?: return
        state.handle.filePriority(fileIndex, Priority.IGNORE)
    }

    /** Stops downloading a torrent and cleans up all associated state. */
    fun removeTorrent(infoHash: String) {
        val state = states.remove(infoHash) ?: return
        // Save resume data before removal so the next open skips re-hashing
        if (state.handle.isValid) {
            try { state.handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT) } catch (_: Exception) {}
        }
        session?.remove(state.handle)
        pieceWaiters.remove(infoHash)
        Log.d(TAG, "Removed torrent $infoHash")
    }

    /**
     * Requests fastresume saves for all active torrents without stopping the session.
     * Call this when the app goes to background so the latest piece state is persisted
     * before Android kills the process.
     */
    fun saveAllResumeData() {
        states.forEach { (_, state) ->
            if (state.handle.isValid) {
                try { state.handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT) } catch (_: Exception) {}
            }
        }
        Log.d(TAG, "Requested resume data save for ${states.size} active torrent(s)")
    }

    fun shutdown() {
        val s = session ?: return
        if (!s.isRunning) return

        // Request resume data for all active torrents; alerts will be processed
        // by the still-running listener before session.stop() drains it.
        states.forEach { (_, state) ->
            if (state.handle.isValid) {
                try { state.handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT) } catch (_: Exception) {}
            }
        }
        // Brief pause to let SAVE_RESUME_DATA alerts arrive and be written to disk
        Thread.sleep(800)

        s.stop()
        Log.i(TAG, "libtorrent session stopped")
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
     * Emits pre-buffer progress [0.0..1.0] for the first [targetPieces] pieces of [fileIndex].
     * The flow **completes** (doesn't loop) once all [targetPieces] pieces are downloaded,
     * so callers can simply `collect` and proceed when it returns.
     * Emits 1.0 immediately if the torrent/file state is unknown (skip pre-buffering).
     */
    fun waitForInitialPiecesFlow(infoHash: String, fileIndex: Int, targetPieces: Int): Flow<Float> = flow {
        val state = states[infoHash]
        if (state == null) { emit(1f); return@flow }
        val (firstPiece, lastPiece) = filePieceRange(state, fileIndex) ?: run { emit(1f); return@flow }
        // Cap to the actual piece count of the file so small files (< targetPieces pieces)
        // don't loop forever waiting for pieces that can never arrive.
        val actualTarget = minOf(targetPieces, lastPiece - firstPiece + 1)
        val endPiece = firstPiece + actualTarget
        while (true) {
            val ready = state.downloadedPieces.subSet(firstPiece, endPiece).size
            emit(minOf(ready.toFloat() / actualTarget, 1f))
            if (ready >= actualTarget) return@flow
            delay(200)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Returns true if libtorrent has at least one downloaded (or pending) piece that
     * belongs exclusively or partially to [fileIndex]. Used by [TorrentCacheManager] to
     * distinguish files that were genuinely started from freshly-allocated stubs.
     *
     * Checks both the in-memory [ActiveTorrent.downloadedPieces] set (accurate after
     * [TorrentCheckedAlert]) and libtorrent's live bitfield as a fallback.
     */
    fun hasAnyDownloadedPieceForFile(infoHash: String, fileIndex: Int): Boolean {
        val state = states[infoHash] ?: return false
        val (firstPiece, lastPiece) = filePieceRange(state, fileIndex) ?: return false
        if (state.downloadedPieces.subSet(firstPiece, lastPiece + 1).isNotEmpty()) return true
        return try {
            val bitfield = state.handle.status(TorrentHandle.QUERY_PIECES).pieces()
            for (i in firstPiece..lastPiece) {
                if (bitfield.getBit(i)) return true
            }
            false
        } catch (_: Exception) { false }
    }

    /**
     * Enables sequential background downloading of [fileIndex] by setting its file-level
     * priority to DEFAULT. Call this before starting a folder cache job so libtorrent
     * downloads the whole file, not just pieces boosted by piece-level priority.
     */
    fun enableFileDownload(infoHash: String, fileIndex: Int) {
        val state = states[infoHash] ?: return
        state.handle.filePriority(fileIndex, Priority.DEFAULT)
    }

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

    /**
     * Reads libtorrent's live piece bitfield and adds all already-downloaded pieces to
     * [ActiveTorrent.downloadedPieces]. Called once after restoring from fastresume so
     * that [waitForPiece] never blocks on pieces that are already on disk.
     */
    private fun populateDownloadedPieces(
        infoHash: String,
        handle: TorrentHandle,
        ti: TorrentInfo,
        state: ActiveTorrent,
    ) {
        try {
            val status = handle.status(TorrentHandle.QUERY_PIECES)
            val bitfield = status.pieces()
            val numPieces = ti.numPieces()
            var count = 0
            for (i in 0 until numPieces) {
                if (bitfield.getBit(i)) {
                    state.downloadedPieces.add(i)
                    count++
                }
            }
            Log.d(TAG, "Populated $count/$numPieces downloaded pieces for $infoHash")
        } catch (e: Exception) {
            Log.w(TAG, "Could not populate piece state for $infoHash: ${e.message}")
        }
    }

    /**
     * Queries libtorrent directly for a single piece. Used as a fallback in [waitForPiece]
     * for pieces that may be on disk but not yet in our in-memory set.
     */
    private fun isPieceDownloadedInSession(state: ActiveTorrent, pieceIndex: Int): Boolean {
        return try {
            if (!state.handle.isValid) return false
            val status = state.handle.status(TorrentHandle.QUERY_PIECES)
            status.pieces().getBit(pieceIndex)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fires when libtorrent finishes hash-checking a torrent (both on fresh add without
     * fastresume and after fastresume validation). Populates [ActiveTorrent.downloadedPieces]
     * with every verified piece and releases any [waitForPiece] semaphores for those pieces.
     * This ensures that pieces already on disk are never waited for unnecessarily on restart.
     */
    private fun onTorrentChecked(alert: TorrentCheckedAlert) {
        val hash  = alert.handle().infoHash().toHex()
        val state = states[hash] ?: return
        try {
            val status   = alert.handle().status(TorrentHandle.QUERY_PIECES)
            val bitfield = status.pieces()
            val numPieces = state.info.numPieces()
            var newCount = 0
            for (i in 0 until numPieces) {
                if (bitfield.getBit(i) && state.downloadedPieces.add(i)) {
                    // Release any threads blocked in waitForPiece() for this piece
                    pieceWaiters[hash]?.remove(i)?.forEach { it.release() }
                    newCount++
                }
            }
            if (newCount > 0) Log.d(TAG, "TorrentChecked: populated $newCount new pieces for $hash")
        } catch (e: Exception) {
            Log.w(TAG, "onTorrentChecked: could not read piece state for $hash: ${e.message}")
        }
        _torrentChecked.tryEmit(hash)
    }

    /** Serialises [AddTorrentParams] from a [SaveResumeDataAlert] and writes it to disk. */
    private fun onSaveResumeData(alert: SaveResumeDataAlert) {
        val hash = alert.handle().infoHash().toHex()
        try {
            val params = alert.params().swig()
            val vec    = LibtorrentSwig.write_resume_data_buf_ex(params)
            val bytes  = Vectors.byte_vector2bytes(vec)
            File(metadataDir, "$hash.fastresume").writeBytes(bytes)
            Log.d(TAG, "Fastresume saved for $hash (${bytes.size} B)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write fastresume for $hash: ${e.message}")
        }
    }

    /** Requests an async resume-data save for [infoHash]. Noop if not active. */
    private fun requestSaveResumeData(infoHash: String) {
        val handle = states[infoHash]?.handle ?: return
        if (!handle.isValid) return
        try { handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT) } catch (_: Exception) {}
    }

    // ── Alert handlers ────────────────────────────────────────────────────────

    private fun onPieceFinished(alert: PieceFinishedAlert) {
        val hash  = alert.handle().infoHash().toHex()
        val piece = alert.pieceIndex()
        val state = states[hash] ?: return

        state.downloadedPieces.add(piece)
        pieceWaiters[hash]?.remove(piece)?.forEach { it.release() }

        // Persist resume data periodically so restarts skip re-hashing
        if (state.piecesSinceLastSave.incrementAndGet() >= PIECES_PER_RESUME_SAVE) {
            state.piecesSinceLastSave.set(0)
            requestSaveResumeData(hash)
        }
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
    val piecesSinceLastSave: AtomicInteger = AtomicInteger(0),
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
        waitForPieceRange(filePos, filePos)
        return raf.read().also { b -> if (b != -1) { filePos++; remaining-- } }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0L) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        // Wait for every piece covered by [filePos, filePos+toRead-1].
        // Without this, reads that cross a piece boundary return pre-allocated zeros
        // from libtorrent for the not-yet-downloaded trailing piece, which manifests as
        // a black/corrupted image when Coil reads the whole file in large chunks.
        waitForPieceRange(filePos, filePos + toRead - 1)
        return raf.read(b, off, toRead).also { n -> if (n > 0) { filePos += n; remaining -= n } }
    }

    override fun close() = raf.close()

    private fun waitForPieceRange(fromOffset: Long, toOffset: Long) {
        val firstPiece = engine.byteToPieceIndex(infoHash, fileIndex, fromOffset)
        val lastPiece  = engine.byteToPieceIndex(infoHash, fileIndex, toOffset)
        for (piece in firstPiece..lastPiece) {
            engine.waitForPiece(infoHash, piece)
        }
    }
}
