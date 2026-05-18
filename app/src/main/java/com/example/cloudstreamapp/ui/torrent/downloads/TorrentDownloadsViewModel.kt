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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
        val trackCount: Int get() = tracks.size
        val totalSizeBytes: Long get() = tracks.sumOf { it.sizeBytes }
        val lastDownloadedAt: Long get() = tracks.maxOfOrNull { it.downloadedAt } ?: 0L
    }

    sealed class Event {
        data class OpenGroup(val torrentName: String) : Event()
        data class OpenPlaylist(val playlistId: String) : Event()
    }

    val groups: StateFlow<List<DownloadGroup>> = db.torrentDownloadDao().getAll()
        .map { entities ->
            entities
                .groupBy { it.torrentName }
                .map { (name, tracks) -> DownloadGroup(name, tracks) }
                .sortedByDescending { it.lastDownloadedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun openGroup(group: DownloadGroup) {
        _events.tryEmit(Event.OpenGroup(group.torrentName))
    }

    fun deleteGroup(group: DownloadGroup) {
        viewModelScope.launch {
            group.tracks.forEach { downloadManager.deleteDownload(it.infoHash, it.fileIndex) }
        }
    }

    fun createPlaylist(group: DownloadGroup) {
        viewModelScope.launch {
            val existing = playlistRepo.findByName(group.torrentName)
            if (existing != null) {
                _events.tryEmit(Event.OpenPlaylist(existing.id))
                return@launch
            }
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
