package com.example.cloudstreamapp.ui.torrent.downloads

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
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
class TorrentGroupDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val db: AppDatabase,
    private val downloadManager: TorrentDownloadManager,
    private val playlistRepo: PlaylistRepositoryImpl,
) : ViewModel() {

    val torrentName: String = Uri.decode(savedStateHandle.get<String>("encodedTorrentName") ?: "")

    data class FolderGroup(
        val folderPath: String,
        val displayName: String,
        val tracks: List<TorrentDownloadEntity>,
    )

    sealed class Event {
        data class PlayTrack(val entity: TorrentDownloadEntity) : Event()
        data class OpenPlaylist(val playlistId: String) : Event()
    }

    val folders: StateFlow<List<FolderGroup>> = db.torrentDownloadDao()
        .getByTorrent(torrentName)
        .map { entities ->
            entities
                .groupBy { it.folderPath }
                .map { (fp, tracks) ->
                    FolderGroup(
                        folderPath = fp,
                        displayName = fp.substringAfterLast("/").ifEmpty { fp },
                        tracks = tracks.sortedBy { it.fileName.lowercase() },
                    )
                }
                .sortedBy { it.folderPath.lowercase() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasFolders: StateFlow<Boolean> = folders
        .map { list -> list.size > 1 || list.firstOrNull()?.folderPath?.isNotEmpty() == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

    fun createPlaylist() {
        viewModelScope.launch {
            val allTracks = folders.value.flatMap { it.tracks }
            if (allTracks.isEmpty()) return@launch
            val playlistId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            playlistRepo.create(
                Playlist(
                    id = playlistId,
                    name = torrentName,
                    coverPath = null,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            allTracks.forEach { entity ->
                playlistRepo.saveMediaAndAddToPlaylist(entity.toCloudItem(), playlistId)
            }
            _events.tryEmit(Event.OpenPlaylist(playlistId))
        }
    }
}
