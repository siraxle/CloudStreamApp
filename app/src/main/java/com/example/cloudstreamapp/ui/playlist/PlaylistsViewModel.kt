package com.example.cloudstreamapp.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.example.cloudstreamapp.core.playlist.PlaylistImportExportManager
import com.example.cloudstreamapp.data.playlist.FavoritePlaylistRepositoryImpl
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudType
import com.example.cloudstreamapp.domain.model.Playlist
import com.example.cloudstreamapp.domain.model.PlaylistBundleData
import com.example.cloudstreamapp.domain.model.PlaylistExportData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repo: PlaylistRepositoryImpl,
    private val favoriteRepo: FavoritePlaylistRepositoryImpl,
    private val importExportManager: PlaylistImportExportManager,
) : ViewModel() {

    data class PlaylistUiItem(
        val playlist: Playlist,
        val totalTracks: Int,
        val cachedTracks: Int,
        val downloadingTracks: Int,
        val isFavorite: Boolean = false,
    )

    sealed class ImportResult {
        data class Single(val playlistId: String) : ImportResult()
        data class Multiple(val count: Int) : ImportResult()
        object Error : ImportResult()
    }

    sealed class ExportResult {
        data class Success(val count: Int) : ExportResult()
        object Error : ExportResult()
    }

    private val favoriteOriginalIds: StateFlow<Set<String>> = favoriteRepo.getAll()
        .map { favorites -> favorites.mapNotNull { it.originalPlaylistId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    val playlists: StateFlow<List<PlaylistUiItem>> = combine(
        repo.getAll()
            .flatMapLatest { playlists ->
                if (playlists.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(
                        playlists.map { playlist ->
                            repo.getItemCacheStats(playlist.id).map { (total, cached, downloading) ->
                                PlaylistUiItem(playlist, total, cached, downloading)
                            }
                        }
                    ) { it.toList() }
                }
            },
        favoriteOriginalIds,
    ) { items, favIds ->
        items.map { it.copy(isFavorite = it.playlist.id in favIds) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Selection mode for bulk export ---

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    fun enterSelectionMode() { _isSelectionMode.value = true }

    fun toggleSelection(id: String) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        _selectedIds.value = playlists.value.map { it.playlist.id }.toSet()
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
        _isSelectionMode.value = false
    }

    // --- Bulk export ---

    private val _pendingExportIds = MutableStateFlow<Set<String>>(emptySet())

    private val _bulkExportFileSuggestion = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val bulkExportFileSuggestion: SharedFlow<String> = _bulkExportFileSuggestion.asSharedFlow()

    private val _bulkExportResult = MutableSharedFlow<ExportResult>(extraBufferCapacity = 1)
    val bulkExportResult: SharedFlow<ExportResult> = _bulkExportResult.asSharedFlow()

    fun requestBulkExport() {
        val ids = _selectedIds.value
        _pendingExportIds.value = ids
        val filename = if (ids.size == 1) {
            val name = playlists.value.firstOrNull { it.playlist.id in ids }
                ?.playlist?.name
                ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                ?.take(80)?.ifBlank { "playlist" } ?: "playlist"
            "$name.json"
        } else {
            "playlists_${ids.size}.json"
        }
        _bulkExportFileSuggestion.tryEmit(filename)
    }

    fun bulkExportToUri(uri: Uri) {
        val ids = _pendingExportIds.value
        _pendingExportIds.value = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val entries = ids.mapNotNull { id ->
                    val name = playlists.value.firstOrNull { it.playlist.id == id }?.playlist?.name
                        ?: return@mapNotNull null
                    val pairs = repo.getItemsWithMetadata(id).first()
                    PlaylistBundleData.PlaylistEntry(
                        name = name,
                        tracks = pairs.mapNotNull { (_, cloudItem) ->
                            cloudItem?.let { item ->
                                PlaylistExportData.ExportTrack(
                                    name = item.name,
                                    sourceId = item.path.sourceId,
                                    relativePath = item.path.relativePath,
                                    cloudType = item.path.cloudType.name,
                                    sizeBytes = item.sizeBytes,
                                    mimeType = item.mimeType,
                                )
                            }
                        },
                    )
                }
                importExportManager.writeBundleToUri(uri, entries)
                entries.size
            }.fold(
                onSuccess = { count -> _bulkExportResult.tryEmit(ExportResult.Success(count)) },
                onFailure = { _bulkExportResult.tryEmit(ExportResult.Error) },
            )
            _selectedIds.value = emptySet()
            _isSelectionMode.value = false
        }
    }

    // --- Create ---

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            repo.create(
                Playlist(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    coverPath = null,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    // --- Favorites ---

    fun toggleFavorite(playlistId: String) {
        viewModelScope.launch {
            val existing = favoriteRepo.findByOriginalId(playlistId)
            if (existing != null) {
                favoriteRepo.delete(existing.id)
            } else {
                favoriteRepo.snapshotPlaylist(playlistId)
            }
        }
    }

    // --- Delete ---

    private val _pendingDeleteId = MutableStateFlow<String?>(null)
    val pendingDeleteId: StateFlow<String?> = _pendingDeleteId.asStateFlow()

    fun requestDeletePlaylist(id: String) {
        _pendingDeleteId.value = id
    }

    fun confirmDeletePlaylist() {
        val id = _pendingDeleteId.value ?: return
        _pendingDeleteId.value = null
        viewModelScope.launch { repo.delete(id) }
    }

    fun cancelDeletePlaylist() {
        _pendingDeleteId.value = null
    }

    // --- Import ---

    private val _importResult = MutableSharedFlow<ImportResult>(extraBufferCapacity = 1)
    val importResult: SharedFlow<ImportResult> = _importResult.asSharedFlow()

    fun importFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val parseResult = importExportManager.parseMultiFromUri(uri) ?: run {
                _importResult.tryEmit(ImportResult.Error)
                return@launch
            }
            when (parseResult) {
                is PlaylistImportExportManager.ParseResult.Single -> {
                    val id = importSinglePlaylist(parseResult.data)
                    if (id != null) _importResult.tryEmit(ImportResult.Single(id))
                    else _importResult.tryEmit(ImportResult.Error)
                }
                is PlaylistImportExportManager.ParseResult.Bundle -> {
                    var count = 0
                    for (entry in parseResult.playlists) {
                        val singleData = PlaylistExportData(
                            name = entry.name,
                            exportedAt = System.currentTimeMillis(),
                            tracks = entry.tracks,
                        )
                        if (importSinglePlaylist(singleData) != null) count++
                    }
                    _importResult.tryEmit(ImportResult.Multiple(count))
                }
            }
        }
    }

    private suspend fun importSinglePlaylist(data: PlaylistExportData): String? = runCatching {
        val newPlaylistId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        repo.create(
            Playlist(
                id = newPlaylistId,
                name = data.name,
                coverPath = null,
                createdAt = now,
                updatedAt = now,
            )
        )
        for (track in data.tracks) {
            val cloudType = runCatching { CloudType.valueOf(track.cloudType) }.getOrDefault(CloudType.HTTP)
            val existingId = repo.findMetadataId(track.sourceId, track.relativePath)
            val mediaId = existingId ?: UUID.randomUUID().toString()
            val cloudItem = CloudItem(
                id = mediaId,
                name = track.name,
                path = CloudPath(track.sourceId, track.relativePath, cloudType),
                type = CloudItem.ItemType.FILE,
                sizeBytes = track.sizeBytes,
                mimeType = track.mimeType,
            )
            repo.saveMediaAndAddToPlaylist(cloudItem, newPlaylistId)
        }
        newPlaylistId
    }.getOrNull()
}
