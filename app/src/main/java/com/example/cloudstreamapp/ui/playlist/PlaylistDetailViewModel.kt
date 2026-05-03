package com.example.cloudstreamapp.ui.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.asFlow
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.cloudstreamapp.core.worker.PlaylistCacheWorker
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.PlaylistItem
import com.example.cloudstreamapp.domain.port.SettingsRepositoryPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: PlaylistRepositoryImpl,
    private val settingsRepo: SettingsRepositoryPort,
    private val workManager: WorkManager,
) : ViewModel() {

    private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])

    data class TrackRow(val item: PlaylistItem, val cloudItem: CloudItem?)

    val tracks: StateFlow<List<TrackRow>> = repo.getItemsWithMetadata(playlistId)
        .map { pairs -> pairs.map { (item, cloudItem) -> TrackRow(item, cloudItem) } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlistName: StateFlow<String> = repo.getAll()
        .map { list -> list.firstOrNull { it.id == playlistId }?.name ?: "Плейлист" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Плейлист")

    // Live WorkManager state for this playlist's download job
    val workInfo: StateFlow<WorkInfo?> = workManager
        .getWorkInfosForUniqueWorkLiveData(PlaylistCacheWorker.workName(playlistId))
        .asFlow()
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun triggerDownload() {
        viewModelScope.launch {
            val wifiOnly = settingsRepo.wifiOnlyPrefetch.first()
            workManager.enqueueUniqueWork(
                PlaylistCacheWorker.workName(playlistId),
                // KEEP: don't interrupt a running download; re-enqueue only after completion/failure
                ExistingWorkPolicy.KEEP,
                PlaylistCacheWorker.buildRequest(playlistId, wifiOnly),
            )
        }
    }

    fun removeTrack(itemId: String) {
        viewModelScope.launch { repo.removeItem(itemId) }
    }
}
