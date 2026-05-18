package com.example.cloudstreamapp.ui.torrent.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.core.database.AppDatabase
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.data.torrent.download.TorrentDownloadEntity
import com.example.cloudstreamapp.data.torrent.download.TorrentDownloadManager
import com.example.cloudstreamapp.data.torrent.download.toCloudItem
import com.example.cloudstreamapp.domain.model.Playlist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class TorrentDownloadsViewModel @Inject constructor(
    private val db: AppDatabase,
    private val downloadManager: TorrentDownloadManager,
    private val playlistRepo: PlaylistRepositoryImpl,
) : ViewModel() {

    data class DownloadGroup(
        val torrentName: String,
        val tracks: List<TorrentDownloadEntity>,
    ) {
        val totalSizeBytes: Long get() = tracks.sumOf { it.sizeBytes }
    }

    sealed class Event {
        data class PlayTrack(val entity: TorrentDownloadEntity) : Event()
        data class OpenPlaylist(val playlistId: String) : Event()
    }

    val groups: StateFlow<List<DownloadGroup>> = db.torrentDownloadDao().getAll()
        .map { entities ->
            entities
                .groupBy { it.torrentName }
                .map { (name, tracks) ->
                    DownloadGroup(name, tracks.sortedBy { it.fileName.lowercase() })
                }
                .sortedByDescending { group ->
                    group.tracks.maxOfOrNull { it.downloadedAt } ?: 0L
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _pendingDelete = MutableStateFlow<TorrentDownloadEntity?>(null)
    val pendingDelete: StateFlow<TorrentDownloadEntity?> = _pendingDelete.asStateFlow()

    fun playTrack(entity: TorrentDownloadEntity) {
        _events.tryEmit(Event.PlayTrack(entity))
    }

    fun requestDelete(entity: TorrentDownloadEntity) {
        _pendingDelete.value = entity
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    fun confirmDelete() {
        val entity = _pendingDelete.value ?: return
        _pendingDelete.value = null
        viewModelScope.launch {
            downloadManager.deleteDownload(entity.infoHash, entity.fileIndex)
        }
    }

    fun deleteGroup(group: DownloadGroup) {
        viewModelScope.launch {
            group.tracks.forEach { downloadManager.deleteDownload(it.infoHash, it.fileIndex) }
        }
    }

    /** Creates a playlist with all tracks in [group], then navigates to it. */
    fun createPlaylist(group: DownloadGroup) {
        viewModelScope.launch {
            val playlistId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            playlistRepo.create(
                Playlist(
                    id = playlistId,
                    name = group.torrentName,
                    coverPath = null,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            group.tracks.forEach { entity ->
                playlistRepo.saveMediaAndAddToPlaylist(entity.toCloudItem(), playlistId)
            }
            _events.tryEmit(Event.OpenPlaylist(playlistId))
        }
    }
}
