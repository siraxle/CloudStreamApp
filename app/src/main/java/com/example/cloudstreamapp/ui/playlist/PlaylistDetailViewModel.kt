package com.example.cloudstreamapp.ui.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.Playlist
import com.example.cloudstreamapp.domain.model.PlaylistItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: PlaylistRepositoryImpl,
) : ViewModel() {

    private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])

    data class TrackRow(val item: PlaylistItem, val cloudItem: CloudItem?)

    val tracks: StateFlow<List<TrackRow>> = repo.getItemsWithMetadata(playlistId)
        .map { pairs -> pairs.map { (item, cloudItem) -> TrackRow(item, cloudItem) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlistName: StateFlow<String> = repo.getAll()
        .map { list -> list.firstOrNull { it.id == playlistId }?.name ?: "Плейлист" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Плейлист")

    fun removeTrack(itemId: String) {
        viewModelScope.launch { repo.removeItem(itemId) }
    }
}
