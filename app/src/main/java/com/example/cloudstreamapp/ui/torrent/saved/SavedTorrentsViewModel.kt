package com.example.cloudstreamapp.ui.torrent.saved

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.data.torrent.saved.SavedTorrentEntity
import com.example.cloudstreamapp.data.torrent.saved.SavedTorrentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SavedTorrentsViewModel @Inject constructor(
    private val repo: SavedTorrentRepository,
) : ViewModel() {

    val torrents: StateFlow<List<SavedTorrentEntity>> = repo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(infoHash: String) {
        viewModelScope.launch { repo.delete(infoHash) }
    }
}
