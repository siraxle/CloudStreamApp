package com.example.cloudstreamapp.ui.torrent.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.core.database.AppDatabase
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.data.torrent.download.toCloudItem
import com.example.cloudstreamapp.data.torrent.local.LocalTorrentEntity
import com.example.cloudstreamapp.data.torrent.local.LocalTorrentRepository
import com.example.cloudstreamapp.domain.model.Playlist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class LocalTorrentsViewModel @Inject constructor(
    private val repo: LocalTorrentRepository,
    private val db: AppDatabase,
    private val playlistRepo: PlaylistRepositoryImpl,
) : ViewModel() {

    val torrents: StateFlow<List<LocalTorrentEntity>> = repo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    sealed class Event {
        data class OpenPlaylist(val playlistId: String) : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun delete(infoHash: String) {
        viewModelScope.launch { repo.delete(infoHash) }
    }

    fun createPlaylist(entry: LocalTorrentEntity) {
        viewModelScope.launch {
            val tracks = db.torrentDownloadDao().getByInfoHash(entry.infoHash)
            if (tracks.isEmpty()) return@launch
            val existing = playlistRepo.findByName(entry.torrentName)
            if (existing != null) {
                _events.tryEmit(Event.OpenPlaylist(existing.id))
                return@launch
            }
            val playlistId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            playlistRepo.create(
                Playlist(
                    id = playlistId,
                    name = entry.torrentName,
                    coverPath = null,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            tracks.forEach { entity ->
                playlistRepo.saveMediaAndAddToPlaylist(entity.toCloudItem(), playlistId)
            }
            _events.tryEmit(Event.OpenPlaylist(playlistId))
        }
    }
}
