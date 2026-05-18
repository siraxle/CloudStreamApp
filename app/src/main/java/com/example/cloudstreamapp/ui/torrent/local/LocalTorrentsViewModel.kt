package com.example.cloudstreamapp.ui.torrent.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.data.torrent.local.LocalTorrentEntity
import com.example.cloudstreamapp.data.torrent.local.LocalTorrentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocalTorrentsViewModel @Inject constructor(
    private val repo: LocalTorrentRepository,
) : ViewModel() {

    val torrents: StateFlow<List<LocalTorrentEntity>> = repo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(infoHash: String) {
        viewModelScope.launch { repo.delete(infoHash) }
    }
}
