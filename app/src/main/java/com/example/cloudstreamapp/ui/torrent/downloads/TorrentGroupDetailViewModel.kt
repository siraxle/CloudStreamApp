package com.example.cloudstreamapp.ui.torrent.downloads

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.core.database.AppDatabase
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.data.torrent.download.TorrentDownloadEntity
import com.example.cloudstreamapp.data.torrent.download.TorrentDownloadManager
import com.example.cloudstreamapp.data.torrent.download.toCloudItem
import com.example.cloudstreamapp.domain.model.Playlist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class TorrentGroupDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val db: AppDatabase,
    private val downloadManager: TorrentDownloadManager,
    private val playlistRepo: PlaylistRepositoryImpl,
) : ViewModel() {

    val torrentName: String = Uri.decode(savedStateHandle.get<String>("encodedTorrentName") ?: "")

    sealed class BrowseItem {
        data class Folder(
            val name: String,
            val fullPath: String,
            val trackCount: Int,
            val totalSizeBytes: Long,
        ) : BrowseItem()

        data class Track(val entity: TorrentDownloadEntity) : BrowseItem()
    }

    data class Breadcrumb(val name: String, val path: String)

    sealed class Event {
        data class PlayTrack(val entity: TorrentDownloadEntity) : Event()
        data class OpenPlaylist(val playlistId: String) : Event()
    }

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val allEntities: StateFlow<List<TorrentDownloadEntity>> = db.torrentDownloadDao()
        .getByTorrent(torrentName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val items: StateFlow<List<BrowseItem>> = combine(allEntities, _currentPath) { entities, path ->
        buildItemsAt(entities, path)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val breadcrumbs: StateFlow<List<Breadcrumb>> = _currentPath.map { path ->
        buildBreadcrumbs(path)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf(Breadcrumb(torrentName, "")))

    val canNavigateUp: StateFlow<Boolean> = _currentPath
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _pendingDelete = MutableStateFlow<TorrentDownloadEntity?>(null)
    val pendingDelete: StateFlow<TorrentDownloadEntity?> = _pendingDelete.asStateFlow()

    private val _pendingDeleteFolder = MutableStateFlow<BrowseItem.Folder?>(null)
    val pendingDeleteFolder: StateFlow<BrowseItem.Folder?> = _pendingDeleteFolder.asStateFlow()

    fun navigateInto(folder: BrowseItem.Folder) {
        _currentPath.value = folder.fullPath
    }

    fun navigateUp() {
        val cp = _currentPath.value
        if (cp.isEmpty()) return
        _currentPath.value = cp.substringBeforeLast("/", "")
    }

    fun navigateTo(path: String) {
        _currentPath.value = path
    }

    fun playTrack(entity: TorrentDownloadEntity) {
        _events.tryEmit(Event.PlayTrack(entity))
    }

    fun requestDelete(entity: TorrentDownloadEntity) {
        _pendingDelete.value = entity
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    fun confirmDelete() {
        val entity = _pendingDelete.value ?: return
        _pendingDelete.value = null
        viewModelScope.launch {
            downloadManager.deleteDownload(entity.infoHash, entity.fileIndex)
        }
    }

    fun requestDeleteFolder(folder: BrowseItem.Folder) {
        _pendingDeleteFolder.value = folder
    }

    fun cancelDeleteFolder() {
        _pendingDeleteFolder.value = null
    }

    fun confirmDeleteFolder() {
        val folder = _pendingDeleteFolder.value ?: return
        _pendingDeleteFolder.value = null
        viewModelScope.launch {
            val toDelete = allEntities.value.filter { e ->
                e.folderPath == folder.fullPath || e.folderPath.startsWith("${folder.fullPath}/")
            }
            toDelete.forEach { downloadManager.deleteDownload(it.infoHash, it.fileIndex) }
        }
    }

    fun createPlaylist() {
        viewModelScope.launch {
            val allTracks = allEntities.value
            if (allTracks.isEmpty()) return@launch
            val playlistId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            playlistRepo.create(
                Playlist(
                    id = playlistId,
                    name = torrentName,
                    coverPath = null,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            allTracks.forEach { entity ->
                playlistRepo.saveMediaAndAddToPlaylist(entity.toCloudItem(), playlistId)
            }
            _events.tryEmit(Event.OpenPlaylist(playlistId))
        }
    }

    private fun buildItemsAt(entities: List<TorrentDownloadEntity>, currentPath: String): List<BrowseItem> {
        val prefix = if (currentPath.isEmpty()) "" else "$currentPath/"

        // Immediate child folder names at this level
        val childFolderNames = entities
            .map { it.folderPath }
            .filter { fp ->
                if (currentPath.isEmpty()) fp.isNotEmpty()
                else fp.startsWith(prefix) && fp.length > prefix.length
            }
            .map { fp ->
                val remaining = if (currentPath.isEmpty()) fp else fp.removePrefix(prefix)
                remaining.substringBefore("/")
            }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        val folders = childFolderNames.map { childName ->
            val childPath = if (currentPath.isEmpty()) childName else "$currentPath/$childName"
            val childEntities = entities.filter { e ->
                e.folderPath == childPath || e.folderPath.startsWith("$childPath/")
            }
            BrowseItem.Folder(
                name = childName,
                fullPath = childPath,
                trackCount = childEntities.size,
                totalSizeBytes = childEntities.sumOf { it.sizeBytes },
            )
        }

        val tracks = entities
            .filter { it.folderPath == currentPath }
            .sortedBy { it.fileName.lowercase() }
            .map { BrowseItem.Track(it) }

        return folders + tracks
    }

    private fun buildBreadcrumbs(currentPath: String): List<Breadcrumb> {
        if (currentPath.isEmpty()) return listOf(Breadcrumb(torrentName, ""))
        val segments = currentPath.split("/")
        return buildList {
            add(Breadcrumb(torrentName, ""))
            segments.forEachIndexed { i, seg ->
                add(Breadcrumb(seg, segments.take(i + 1).joinToString("/")))
            }
        }
    }
}
