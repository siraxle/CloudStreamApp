package com.example.cloudstreamapp.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.data.playlist.FavoritePlaylistRepositoryImpl
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.domain.model.Playlist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    private val _pendingDeleteId = MutableStateFlow<String?>(null)
    val pendingDeleteId: StateFlow<String?> = _pendingDeleteId.asStateFlow()

    fun requestDeletePlaylist(id: String) { _pendingDeleteId.value = id }

    fun confirmDeletePlaylist() {
        val id = _pendingDeleteId.value ?: return
        _pendingDeleteId.value = null
        viewModelScope.launch { repo.delete(id) }
    }

    fun cancelDeletePlaylist() { _pendingDeleteId.value = null }
}
