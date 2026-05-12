package com.example.cloudstreamapp.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.data.playlist.FavoritePlaylistRepositoryImpl
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.domain.model.FavoritePlaylist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

    data class FavoriteUiItem(
        val favorite: FavoritePlaylist,
        val isInMainList: Boolean,
    )

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

    // Emits the new playlist ID after a successful restore so the screen can navigate to it
    private val _restoredPlaylistId = MutableStateFlow<String?>(null)
    val restoredPlaylistId: StateFlow<String?> = _restoredPlaylistId.asStateFlow()

    fun restore(favoriteId: String) {
        viewModelScope.launch {
            val newId = favoriteRepo.restoreFavorite(favoriteId)
            if (newId != null) _restoredPlaylistId.value = newId
        }
    }

    fun consumeRestoredPlaylistId() {
        _restoredPlaylistId.value = null
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
