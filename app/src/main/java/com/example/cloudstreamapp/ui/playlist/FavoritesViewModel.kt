package com.example.cloudstreamapp.ui.playlist

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.core.playlist.PlaylistImportExportManager
import com.example.cloudstreamapp.data.playlist.FavoritePlaylistRepositoryImpl
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.domain.model.FavoritePlaylist
import com.example.cloudstreamapp.domain.model.PlaylistExportData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoriteRepo: FavoritePlaylistRepositoryImpl,
    private val playlistRepo: PlaylistRepositoryImpl,
    private val importExportManager: PlaylistImportExportManager,
) : ViewModel() {

    data class FavoriteUiItem(
        val favorite: FavoritePlaylist,
        val isInMainList: Boolean,
    )

    sealed class ExportResult {
        object Success : ExportResult()
        object Error : ExportResult()
    }

    val favorites: StateFlow<List<FavoriteUiItem>> = combine(
        favoriteRepo.getAll(),
        playlistRepo.getAll().map { it.map { p -> p.id }.toSet() },
    ) { favorites, activeIds ->
        favorites.map { favorite ->
            FavoriteUiItem(
                favorite = favorite,
                isInMainList = favorite.originalPlaylistId != null &&
                    favorite.originalPlaylistId in activeIds,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _pendingDeleteId = MutableStateFlow<String?>(null)
    val pendingDeleteId: StateFlow<String?> = _pendingDeleteId.asStateFlow()

    private val _restoredPlaylistId = MutableStateFlow<String?>(null)
    val restoredPlaylistId: StateFlow<String?> = _restoredPlaylistId.asStateFlow()

    // Holds the favoriteId to export while waiting for the file-save URI from the picker
    private val _pendingExportId = MutableStateFlow<String?>(null)

    // Emits the suggested filename to trigger the system file-save dialog in the Composable
    private val _exportFileSuggestion = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val exportFileSuggestion: SharedFlow<String> = _exportFileSuggestion.asSharedFlow()

    private val _exportResult = MutableSharedFlow<ExportResult>(extraBufferCapacity = 1)
    val exportResult: SharedFlow<ExportResult> = _exportResult.asSharedFlow()

    fun restore(favoriteId: String) {
        viewModelScope.launch {
            val newId = favoriteRepo.restoreFavorite(favoriteId)
            if (newId != null) _restoredPlaylistId.value = newId
        }
    }

    fun consumeRestoredPlaylistId() {
        _restoredPlaylistId.value = null
    }

    fun requestExport(favoriteId: String) {
        val name = favorites.value
            .firstOrNull { it.favorite.id == favoriteId }
            ?.favorite?.name
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            ?.take(80)
            ?.ifBlank { "playlist" }
            ?: "playlist"
        _pendingExportId.value = favoriteId
        _exportFileSuggestion.tryEmit("$name.json")
    }

    fun exportToUri(uri: Uri) {
        val favoriteId = _pendingExportId.value ?: return
        _pendingExportId.value = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val favorite = favoriteRepo.getById(favoriteId)
                    ?: error("Favorite not found")
                val exportData = PlaylistExportData(
                    name = favorite.name,
                    exportedAt = System.currentTimeMillis(),
                    tracks = favorite.tracks.map { track ->
                        PlaylistExportData.ExportTrack(
                            name = track.name,
                            sourceId = track.sourceId,
                            relativePath = track.relativePath,
                            cloudType = track.cloudType,
                            sizeBytes = track.sizeBytes,
                            mimeType = track.mimeType,
                        )
                    },
                )
                importExportManager.writeToUri(uri, exportData)
            }.fold(
                onSuccess = { _exportResult.tryEmit(ExportResult.Success) },
                onFailure = { _exportResult.tryEmit(ExportResult.Error) },
            )
        }
    }

    fun requestDelete(id: String) {
        _pendingDeleteId.value = id
    }

    fun confirmDelete() {
        val id = _pendingDeleteId.value ?: return
        _pendingDeleteId.value = null
        viewModelScope.launch { favoriteRepo.delete(id) }
    }

    fun cancelDelete() {
        _pendingDeleteId.value = null
    }
}
