package com.example.cloudstreamapp.ui.playlist

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
        data class AlreadyExists(val playlistId: String) : ImportResult()
        data class Multiple(val imported: Int, val skipped: Int) : ImportResult()
        object Error : ImportResult()
    }

    private sealed class SingleImportOutcome {
        data class Created(val id: String) : SingleImportOutcome()
        data class Existed(val id: String) : SingleImportOutcome()
        object Failed : SingleImportOutcome()
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

    fun requestDeletePlaylist(id: String) { _pendingDeleteId.value = id }

    fun confirmDeletePlaylist() {
        val id = _pendingDeleteId.value ?: return
        _pendingDeleteId.value = null
        viewModelScope.launch { repo.delete(id) }
    }

    fun cancelDeletePlaylist() { _pendingDeleteId.value = null }

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

    private val _pendingBulkExportIds = MutableStateFlow<Set<String>>(emptySet())

    private val _bulkExportFileSuggestion = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val bulkExportFileSuggestion: SharedFlow<String> = _bulkExportFileSuggestion.asSharedFlow()

    private val _bulkExportResult = MutableSharedFlow<ExportResult>(extraBufferCapacity = 1)
    val bulkExportResult: SharedFlow<ExportResult> = _bulkExportResult.asSharedFlow()

    fun requestBulkExport() {
        val ids = _selectedIds.value
        _pendingBulkExportIds.value = ids
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
        val ids = _pendingBulkExportIds.value
        _pendingBulkExportIds.value = emptySet()
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
                    val outcome = importSinglePlaylist(parseResult.data)
                    _importResult.tryEmit(
                        when (outcome) {
                            is SingleImportOutcome.Created -> ImportResult.Single(outcome.id)
                            is SingleImportOutcome.Existed -> ImportResult.AlreadyExists(outcome.id)
                            SingleImportOutcome.Failed -> ImportResult.Error
                        }
                    )
                }
                is PlaylistImportExportManager.ParseResult.Bundle -> {
                    var imported = 0
                    var skipped = 0
                    for (entry in parseResult.playlists) {
                        val singleData = PlaylistExportData(
                            name = entry.name,
                            exportedAt = System.currentTimeMillis(),
                            tracks = entry.tracks,
                        )
                        when (importSinglePlaylist(singleData)) {
                            is SingleImportOutcome.Created -> imported++
                            is SingleImportOutcome.Existed -> skipped++
                            SingleImportOutcome.Failed -> Unit
                        }
                    }
                    _importResult.tryEmit(ImportResult.Multiple(imported, skipped))
                }
            }
        }
    }

    private suspend fun importSinglePlaylist(data: PlaylistExportData): SingleImportOutcome {
        val existingId = findDuplicatePlaylist(data)
        if (existingId != null) return SingleImportOutcome.Existed(existingId)
        return runCatching {
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
                val existingMediaId = repo.findMetadataId(track.sourceId, track.relativePath)
                val mediaId = existingMediaId ?: UUID.randomUUID().toString()
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
            SingleImportOutcome.Created(newPlaylistId)
        }.getOrElse { SingleImportOutcome.Failed }
    }

    private suspend fun findDuplicatePlaylist(data: PlaylistExportData): String? {
        val incomingKeys = data.tracks.map { "${it.sourceId}|${it.relativePath}" }.toSet()
        val existing = repo.getAll().first()
        for (playlist in existing) {
            if (playlist.name != data.name) continue
            val items = repo.getItemsWithMetadata(playlist.id).first()
            val existingKeys = items.mapNotNull { (_, cloudItem) ->
                cloudItem?.let { "${it.path.sourceId}|${it.path.relativePath}" }
            }.toSet()
            if (existingKeys == incomingKeys) return playlist.id
        }
        return null
    }
}
