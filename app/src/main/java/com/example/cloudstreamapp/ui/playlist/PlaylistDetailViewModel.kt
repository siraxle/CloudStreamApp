package com.example.cloudstreamapp.ui.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.cloudstreamapp.core.cache.MediaCacheManager
import com.example.cloudstreamapp.core.worker.PlaylistCacheWorker
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.PlaylistItem
import com.example.cloudstreamapp.domain.port.SettingsRepositoryPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: PlaylistRepositoryImpl,
    private val cacheManager: MediaCacheManager,
    private val settingsRepo: SettingsRepositoryPort,
    private val workManager: WorkManager,
) : ViewModel() {

    private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])
    private val workerTag = PlaylistCacheWorker.workName(playlistId)

    data class TrackRow(val item: PlaylistItem, val cloudItem: CloudItem?)

    // Incremented each time the worker reports progress — triggers cache status re-check
    private val _cacheTick = MutableStateFlow(0)

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    val tracks: StateFlow<List<TrackRow>> = combine(
        repo.getItemsWithMetadata(playlistId),
        _cacheTick,
    ) { pairs, _ ->
        pairs.map { (item, cloudItem) ->
            // Re-read cache status on every tick so icons update during download
            TrackRow(
                item,
                cloudItem?.copy(
                    cacheStatus = cacheManager.getCacheStatus(cloudItem.id, cloudItem.sizeBytes)
                ),
            )
        }
    }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlistName: StateFlow<String> = repo.getAll()
        .map { list -> list.firstOrNull { it.id == playlistId }?.name ?: "Плейлист" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Плейлист")

    init {
        enqueueCache()
        observeWorker()
    }

    private fun enqueueCache() {
        viewModelScope.launch {
            val wifiOnly = settingsRepo.wifiOnlyPrefetch.first()
            workManager.enqueueUniqueWork(
                workerTag,
                ExistingWorkPolicy.KEEP,
                PlaylistCacheWorker.buildRequest(playlistId, wifiOnly),
            )
        }
    }

    private fun observeWorker() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(workerTag)
                .collect { infos ->
                    _isDownloading.value = infos.any { it.state == WorkInfo.State.RUNNING }
                    // Each WorkInfo change (progress update or completion) refreshes icons
                    _cacheTick.value++
                }
        }
    }

    fun removeTrack(itemId: String) {
        viewModelScope.launch { repo.removeItem(itemId) }
    }
}
