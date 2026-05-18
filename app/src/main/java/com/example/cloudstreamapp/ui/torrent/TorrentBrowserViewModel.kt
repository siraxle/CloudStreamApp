package com.example.cloudstreamapp.ui.torrent

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.data.torrent.TorrentCloudProvider
import com.example.cloudstreamapp.data.torrent.TorrentRepository
import com.example.cloudstreamapp.data.torrent.download.TorrentDownloadManager
import com.example.cloudstreamapp.data.torrent.provider.ContentCategory
import com.example.cloudstreamapp.data.torrent.provider.TorrentSource
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudResult
import com.example.cloudstreamapp.domain.torrent.DownloadProgress
import com.example.cloudstreamapp.domain.torrent.TorrentResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
) : ViewModel() {

    companion object {
        private const val PAGE_SIZE = 30
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
        savedStateHandle.get<String>("magnetUri")?.let { openMagnet(Uri.decode(it)) }
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
        }
    }

    private fun sourcesFromResults(results: List<TorrentResult>): List<TorrentSource> =
        TorrentSource.entries.filter { src -> results.any { it.source == src.displayName } }

    fun currentMagnetUri(): String = currentMagnetUri

    // ── Download actions ──────────────────────────────────────────────────────

    /** Live map of download progress keyed by "${infoHash}:${fileIndex}". */
    val downloadProgress: StateFlow<Map<String, DownloadProgress>> =
        downloadManager.progress.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Starts downloading a single audio file to the device's Music folder. */
    fun downloadFile(item: CloudItem) {
        val current = _uiState.value as? UiState.FileList ?: return
        val parts = item.id.split(":")
        if (parts.size < 2) return
        downloadManager.downloadFile(
            infoHash = parts[0],
            fileIndex = parts[1].toInt(),
            fileName = item.name,
            sizeBytes = item.sizeBytes ?: 0L,
            torrentName = current.torrentName,
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
