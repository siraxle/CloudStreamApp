package com.example.cloudstreamapp.ui.torrent

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.data.torrent.TorrentCloudProvider
import com.example.cloudstreamapp.data.torrent.TorrentRepository
import com.example.cloudstreamapp.data.torrent.download.TorrentDownloadManager
import com.example.cloudstreamapp.data.torrent.local.LocalTorrentRepository
import com.example.cloudstreamapp.data.torrent.provider.ContentCategory
import com.example.cloudstreamapp.data.torrent.provider.TorrentSource
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudResult
import com.example.cloudstreamapp.domain.torrent.DownloadProgress
import com.example.cloudstreamapp.domain.torrent.TorrentResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TorrentBrowserViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val provider: TorrentCloudProvider,
    private val repository: TorrentRepository,
    private val downloadManager: TorrentDownloadManager,
    private val localTorrentRepo: LocalTorrentRepository,
) : ViewModel() {

    companion object {
        private const val PAGE_SIZE = 30

        // SavedStateHandle keys for restoring the open torrent across ViewModel recreation
        private const val KEY_INFO_HASH = "fl_infoHash"
        private const val KEY_MAGNET_URI = "fl_magnetUri"
        private const val KEY_TORRENT_NAME = "fl_torrentName"
        private const val KEY_FOLDER_PATH = "fl_folderPath"
        private const val KEY_PAGE = "fl_page"
    }

    sealed class UiState {
        object Idle : UiState()
        data class Searching(val query: String) : UiState()
        data class SearchResults(
            val query: String,
            val results: List<TorrentResult>,
            val activeFilter: TorrentSource?,
            // Sources present across all (unfiltered) results — kept stable so chips
            // don't disappear when the user drills into a single source.
            val availableSources: List<TorrentSource>,
            val totalCount: Int,
        ) : UiState()
        data class ResolvingMagnet(val name: String) : UiState()
        data class FileList(
            val pageItems: List<CloudItem>,
            val infoHash: String,
            val magnetUri: String,
            val torrentName: String,
            /** Current folder within the torrent: "" = root, "Album/Disc1" = subfolder. */
            val currentPath: String,
            val page: Int,
            val totalPages: Int,
            /** Total items in the current folder (folders + files). */
            val totalCount: Int,
        ) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _category = MutableStateFlow(ContentCategory.AUDIO)
    val category: StateFlow<ContentCategory> = _category.asStateFlow()

    // Full unfiltered results kept in memory for instant re-filtering without a new network call
    private var fullResults: List<TorrentResult> = emptyList()
    private var currentMagnetUri: String = ""

    init {
        // Legacy: open from navigation argument (currently unused for this route)
        savedStateHandle.get<String>("magnetUri")?.let { openMagnet(Uri.decode(it)) }

        // Restore the file list if the ViewModel was recreated while the user was viewing a torrent
        // (happens when switching bottom-nav tabs: saveState/restoreState recreates the VM but
        // preserves the SavedStateHandle bundle, so we can re-fetch the same folder page)
        val restoredInfoHash = savedStateHandle.get<String>(KEY_INFO_HASH)
        val restoredMagnet   = savedStateHandle.get<String>(KEY_MAGNET_URI)
        val restoredName     = savedStateHandle.get<String>(KEY_TORRENT_NAME)
        if (restoredInfoHash != null && restoredMagnet != null && restoredName != null) {
            val restoredPath = savedStateHandle.get<String>(KEY_FOLDER_PATH) ?: ""
            val restoredPage = savedStateHandle.get<Int>(KEY_PAGE) ?: 1
            currentMagnetUri = restoredMagnet
            loadFolderPage(restoredInfoHash, restoredMagnet, restoredName, restoredPath, restoredPage)
        }
    }

    fun onQueryChanged(value: String) {
        _query.value = value
    }

    fun search(queryOverride: String? = null) {
        val q = (queryOverride ?: _query.value).trim()
        if (q.isBlank()) return

        if (q.startsWith("magnet:")) {
            openMagnet(q)
            return
        }

        clearSavedFileListState()
        _uiState.value = UiState.Searching(q)
        viewModelScope.launch {
            val results = runCatching { repository.search(q, category = _category.value) }.getOrElse { emptyList() }
            fullResults = results
            _uiState.value = UiState.SearchResults(
                query = q,
                results = results,
                activeFilter = null,
                availableSources = sourcesFromResults(results),
                totalCount = results.size,
            )
        }
    }

    fun filterBySource(source: TorrentSource?) {
        val current = _uiState.value as? UiState.SearchResults ?: return
        val filtered = if (source == null) fullResults
                       else fullResults.filter { it.source == source.displayName }
        // availableSources and totalCount stay the same — chips must remain visible
        _uiState.value = current.copy(results = filtered, activeFilter = source)
    }

    fun setCategory(cat: ContentCategory) {
        if (_category.value == cat) return
        _category.value = cat
        val q = _query.value.trim()
        if (q.isNotBlank() && _uiState.value !is UiState.Idle) search(q)
    }

    fun openTorrentResult(result: TorrentResult) {
        _uiState.value = UiState.ResolvingMagnet(result.name)
        currentMagnetUri = result.magnetUri
        viewModelScope.launch {
            runCatching { provider.resolve(result.magnetUri) }
                .onSuccess { cloudResult ->
                    when (cloudResult) {
                        is CloudResult.FolderResult ->
                            loadFolderPage(cloudResult.path.relativePath, result.magnetUri, result.name, "", 1)
                        is CloudResult.Error -> _uiState.value = UiState.Error(cloudResult.message)
                        else -> _uiState.value = UiState.Error("Unexpected result type")
                    }
                }
                .onFailure { _uiState.value = UiState.Error(it.message ?: "Failed to resolve torrent") }
        }
    }

    fun openMagnet(magnetUri: String = _query.value.trim()) {
        if (magnetUri.isBlank()) return
        currentMagnetUri = magnetUri
        val torrentName = Uri.decode(
            magnetUri.substringAfter("dn=").substringBefore("&")
        ).ifBlank { "Torrent" }
        _uiState.value = UiState.ResolvingMagnet(torrentName)
        viewModelScope.launch {
            runCatching { provider.resolve(magnetUri) }
                .onSuccess { cloudResult ->
                    when (cloudResult) {
                        is CloudResult.FolderResult ->
                            loadFolderPage(cloudResult.path.relativePath, magnetUri, torrentName, "", 1)
                        is CloudResult.Error -> _uiState.value = UiState.Error(cloudResult.message)
                        else -> _uiState.value = UiState.Error("Unexpected result type")
                    }
                }
                .onFailure { _uiState.value = UiState.Error(it.message ?: "Failed to resolve magnet") }
        }
    }

    /** Navigates into a directory item shown in the file list. */
    fun navigateToFolder(item: CloudItem) {
        val current = _uiState.value as? UiState.FileList ?: return
        loadFolderPage(current.infoHash, current.magnetUri, current.torrentName, item.path.relativePath, 1)
    }

    /** Navigates to the parent folder, or back to search results if already at root. */
    fun navigateUp() {
        val current = _uiState.value as? UiState.FileList ?: return
        if (current.currentPath.isEmpty()) {
            // Device-opened .torrent files (magnetUri = "torrent:$infoHash") have no search
            // results to return to — stay on the file list so the torrent is not lost.
            // The user can switch screens via the bottom nav; state is preserved in SavedStateHandle.
            if (current.magnetUri.startsWith("torrent:")) return
            backToResults()
            return
        }
        val parentPath = current.currentPath.substringBeforeLast("/", "")
        loadFolderPage(current.infoHash, current.magnetUri, current.torrentName, parentPath, 1)
    }

    /**
     * Navigates to a breadcrumb entry.
     * [index] 0 = torrent root, 1 = first path component, etc.
     */
    fun navigateToBreadcrumb(index: Int) {
        val current = _uiState.value as? UiState.FileList ?: return
        val targetPath = if (index == 0) ""
                         else current.currentPath.split("/").take(index).joinToString("/")
        if (targetPath == current.currentPath) return
        loadFolderPage(current.infoHash, current.magnetUri, current.torrentName, targetPath, 1)
    }

    /** Loads a specific page within the current folder. */
    fun loadPage(page: Int) {
        val current = _uiState.value as? UiState.FileList ?: return
        loadFolderPage(current.infoHash, current.magnetUri, current.torrentName, current.currentPath, page)
    }

    fun backToResults() {
        clearSavedFileListState()
        if (fullResults.isNotEmpty()) {
            _uiState.value = UiState.SearchResults(
                query = _query.value,
                results = fullResults,
                activeFilter = null,
                availableSources = sourcesFromResults(fullResults),
                totalCount = fullResults.size,
            )
        } else {
            _uiState.value = UiState.Idle
        }
    }

    private fun loadFolderPage(
        infoHash: String,
        magnetUri: String,
        torrentName: String,
        folderPath: String,
        page: Int,
    ) {
        viewModelScope.launch {
            val items = withContext(Dispatchers.Default) {
                provider.listFolderItems(infoHash, folderPath, magnetUri)
            }
            val start = (page - 1) * PAGE_SIZE
            val pageItems = items.drop(start).take(PAGE_SIZE)
            val totalPages = maxOf(1, (items.size + PAGE_SIZE - 1) / PAGE_SIZE)
            _uiState.value = UiState.FileList(
                pageItems = pageItems,
                infoHash = infoHash,
                magnetUri = magnetUri,
                torrentName = torrentName,
                currentPath = folderPath,
                page = page,
                totalPages = totalPages,
                totalCount = items.size,
            )
            // Persist key parameters so the file list survives ViewModel recreation
            // (e.g. when the user switches bottom-nav tabs and comes back)
            savedStateHandle[KEY_INFO_HASH]    = infoHash
            savedStateHandle[KEY_MAGNET_URI]   = magnetUri
            savedStateHandle[KEY_TORRENT_NAME] = torrentName
            savedStateHandle[KEY_FOLDER_PATH]  = folderPath
            savedStateHandle[KEY_PAGE]         = page
        }
    }

    private fun clearSavedFileListState() {
        savedStateHandle.remove<String>(KEY_INFO_HASH)
        savedStateHandle.remove<String>(KEY_MAGNET_URI)
        savedStateHandle.remove<String>(KEY_TORRENT_NAME)
        savedStateHandle.remove<String>(KEY_FOLDER_PATH)
        savedStateHandle.remove<Int>(KEY_PAGE)
    }

    private fun sourcesFromResults(results: List<TorrentResult>): List<TorrentSource> =
        TorrentSource.entries.filter { src -> results.any { it.source == src.displayName } }

    fun currentMagnetUri(): String = currentMagnetUri

    /** Opens a .torrent file from raw bytes, parses metadata, and shows the file list. */
    fun openTorrentFile(bytes: ByteArray, fileName: String) {
        val torrentName = fileName.removeSuffix(".torrent")
        _uiState.value = UiState.ResolvingMagnet(torrentName)
        viewModelScope.launch {
            runCatching { provider.resolveTorrentBytes(bytes, fileName) }
                .onSuccess { cloudResult ->
                    when (cloudResult) {
                        is CloudResult.FolderResult -> {
                            val infoHash = cloudResult.path.relativePath
                            currentMagnetUri = "torrent:$infoHash"
                            localTorrentRepo.save(infoHash, torrentName, fileName, bytes)
                            loadFolderPage(infoHash, "torrent:$infoHash", torrentName, "", 1)
                        }
                        is CloudResult.Error -> _uiState.value = UiState.Error(cloudResult.message)
                        else -> _uiState.value = UiState.Error("Unexpected result type")
                    }
                }
                .onFailure { _uiState.value = UiState.Error(it.message ?: "Failed to open torrent file") }
        }
    }

    /** Opens a previously saved local .torrent file by its infoHash. */
    fun openLocalTorrent(infoHash: String) {
        viewModelScope.launch {
            val entry = localTorrentRepo.findByInfoHash(infoHash) ?: return@launch
            val bytes = localTorrentRepo.getBytes(infoHash) ?: run {
                _uiState.value = UiState.Error("Файл торрента не найден на устройстве")
                return@launch
            }
            _uiState.value = UiState.ResolvingMagnet(entry.torrentName)
            runCatching { provider.resolveTorrentBytes(bytes, entry.fileName) }
                .onSuccess { cloudResult ->
                    when (cloudResult) {
                        is CloudResult.FolderResult -> {
                            currentMagnetUri = "torrent:$infoHash"
                            loadFolderPage(infoHash, "torrent:$infoHash", entry.torrentName, "", 1)
                        }
                        is CloudResult.Error -> _uiState.value = UiState.Error(cloudResult.message)
                        else -> _uiState.value = UiState.Error("Unexpected result type")
                    }
                }
                .onFailure { _uiState.value = UiState.Error(it.message ?: "Failed to open torrent file") }
        }
    }

    // ── Download actions ──────────────────────────────────────────────────────

    /** Live map of download progress keyed by "${infoHash}:${fileIndex}". */
    val downloadProgress: StateFlow<Map<String, DownloadProgress>> =
        downloadManager.progress.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Set of folder paths (e.g. "Album", "Album/Disc1") that have at least one
     * Queued or Downloading file in the current torrent. Recomputed on every
     * progress update so FolderItems can show a cancel button reactively.
     */
    val activeFolderDownloadPaths: StateFlow<Set<String>> = downloadManager.progress
        .map { _ ->
            val state = _uiState.value as? UiState.FileList ?: return@map emptySet()
            downloadManager.activeFolderPaths(state.infoHash)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Starts downloading a single audio file to the device's Music folder. */
    fun downloadFile(item: CloudItem) {
        val current = _uiState.value as? UiState.FileList ?: return
        val parts = item.id.split(":")
        if (parts.size < 2) return
        // relativePath = "TorrentRoot/Sub/File.mp3" → folderPath = "Sub"
        val parentDir = item.path.relativePath.substringBeforeLast("/", "")
        val folderPath = parentDir.substringAfter("/", "")
        downloadManager.downloadFile(
            infoHash = parts[0],
            fileIndex = parts[1].toInt(),
            fileName = item.name,
            sizeBytes = item.sizeBytes ?: 0L,
            torrentName = current.torrentName,
            folderPath = folderPath,
        )
    }

    /** Downloads all audio files inside a folder item (and its subfolders). */
    fun downloadFolderItem(folderItem: CloudItem) {
        val current = _uiState.value as? UiState.FileList ?: return
        downloadManager.downloadFolder(
            infoHash = current.infoHash,
            folderPath = folderItem.path.relativePath,
            torrentName = current.torrentName,
        )
    }

    /** Cancels all active downloads inside a folder item (and its subfolders). */
    fun cancelFolderDownload(folderItem: CloudItem) {
        val current = _uiState.value as? UiState.FileList ?: return
        downloadManager.cancelFolder(
            infoHash = current.infoHash,
            folderPath = folderItem.path.relativePath,
        )
    }

    /** Cancels an in-progress download. */
    fun cancelDownload(item: CloudItem) {
        val parts = item.id.split(":")
        if (parts.size < 2) return
        downloadManager.cancelDownload(parts[0], parts[1].toInt())
    }

    /** Deletes a completed download from storage and the DB record. */
    fun deleteDownload(item: CloudItem) {
        val parts = item.id.split(":")
        if (parts.size < 2) return
        viewModelScope.launch {
            downloadManager.deleteDownload(parts[0], parts[1].toInt())
        }
    }
}
