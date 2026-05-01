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

/** Fraction [0f..1f] of download progress, or null when not downloading. */

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

    // null = not downloading, 0f..1f = fraction complete
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

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
                    val running = infos.any { it.state == WorkInfo.State.RUNNING }
                    _isDownloading.value = running
                    if (running) {
                        val info = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                        val done = info?.progress?.getInt(PlaylistCacheWorker.PROGRESS_INDEX, 0) ?: 0
                        val total = info?.progress?.getInt(PlaylistCacheWorker.PROGRESS_TOTAL, 0) ?: 0
                        _downloadProgress.value = if (total > 0) done.toFloat() / total else null
                    } else {
                        _downloadProgress.value = null
                    }
                    _cacheTick.value++
                }
        }
    }

    fun triggerDownload() {
        viewModelScope.launch {
            val wifiOnly = settingsRepo.wifiOnlyPrefetch.first()
            workManager.enqueueUniqueWork(
                workerTag,
                ExistingWorkPolicy.REPLACE,
                PlaylistCacheWorker.buildRequest(playlistId, wifiOnly),
            )
        }
    }

    fun removeTrack(itemId: String) {
        viewModelScope.launch { repo.removeItem(itemId) }
    }
}
