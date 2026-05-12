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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    // Non-null while waiting for the user to confirm a playlist deletion
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

    // Non-null after a successful import; consumed by the Composable to navigate
    private val _importedPlaylistId = MutableStateFlow<String?>(null)
    val importedPlaylistId: StateFlow<String?> = _importedPlaylistId.asStateFlow()

    private val _importError = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val importError: SharedFlow<Unit> = _importError.asSharedFlow()

    fun importFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val data = importExportManager.parseFromUri(uri) ?: run {
                _importError.tryEmit(Unit)
                return@launch
            }
            runCatching {
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
                    val cloudType = runCatching { CloudType.valueOf(track.cloudType) }
                        .getOrDefault(CloudType.HTTP)
                    // Reuse existing mediaId if this path is already in the DB
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
                _importedPlaylistId.value = newPlaylistId
            }.onFailure {
                _importError.tryEmit(Unit)
            }
        }
    }

    fun consumeImportedPlaylistId() {
        _importedPlaylistId.value = null
    }
}
