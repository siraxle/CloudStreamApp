package com.example.cloudstreamapp.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.domain.model.Playlist
import com.example.cloudstreamapp.domain.port.PlaylistRepositoryPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repo: PlaylistRepositoryPort,
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = repo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun deletePlaylist(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }
}
