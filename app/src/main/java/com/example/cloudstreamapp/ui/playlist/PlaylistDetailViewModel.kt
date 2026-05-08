package com.example.cloudstreamapp.ui.playlist

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.example.cloudstreamapp.core.cache.MediaCacheManager
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.domain.model.CacheStatus
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.PlaylistItem
import com.example.cloudstreamapp.domain.port.SettingsRepositoryPort
import com.example.cloudstreamapp.domain.usecase.GetStreamUrlUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: PlaylistRepositoryImpl,
    private val cacheManager: MediaCacheManager,
    private val settingsRepo: SettingsRepositoryPort,
    private val getStreamUrl: GetStreamUrlUseCase,
    private val okHttpClient: OkHttpClient,
) : ViewModel() {

    private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])

    data class TrackRow(val item: PlaylistItem, val cloudItem: CloudItem?)

    sealed class ItemDownloadState {
        object Idle : ItemDownloadState()
        data class InProgress(val progress: Float?) : ItemDownloadState()
        object Done : ItemDownloadState()
    }

    sealed class DownloadError {
        object CacheLimitReached : DownloadError()
    }

    // Per-file download state: mediaId -> state (live, resets on new session)
    private val _itemStates = MutableStateFlow<Map<String, ItemDownloadState>>(emptyMap())
    val itemDownloadStates: StateFlow<Map<String, ItemDownloadState>> = _itemStates.asStateFlow()

    private val _downloadError = MutableSharedFlow<DownloadError>(extraBufferCapacity = 1)
    val downloadError: SharedFlow<DownloadError> = _downloadError.asSharedFlow()

    // Non-null while waiting for the user to confirm a track removal
    private val _pendingRemoveItemId = MutableStateFlow<String?>(null)
    val pendingRemoveItemId: StateFlow<String?> = _pendingRemoveItemId.asStateFlow()

    val tracks: StateFlow<List<TrackRow>> = repo.getItemsWithMetadata(playlistId)
        .map { pairs -> pairs.map { (item, cloudItem) -> TrackRow(item, cloudItem) } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlistName: StateFlow<String> = repo.getAll()
        .map { list -> list.firstOrNull { it.id == playlistId }?.name ?: "Плейлист" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Плейлист")

    private var downloadJob: Job? = null
    private val singleDownloadJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            cacheManager.cacheCleared.collect {
                _itemStates.value = emptyMap()
            }
        }
    }

    fun triggerDownload() {
        downloadJob?.cancel()
        singleDownloadJobs.values.forEach { it.cancel() }
        singleDownloadJobs.clear()
        _itemStates.value = emptyMap()
        startDownloadAll()
    }

    fun downloadSingleTrack(cloudItem: CloudItem) {
        singleDownloadJobs[cloudItem.id]?.cancel()
        // Set InProgress synchronously before launching so Compose renders it in the very next frame,
        // even if the IO work completes faster than a frame boundary.
        _itemStates.update { it + (cloudItem.id to ItemDownloadState.InProgress(null)) }
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val cacheLimit = settingsRepo.cacheLimitBytes.first()
                if (cacheManager.simpleCache.cacheSpace >= cacheLimit) {
                    _itemStates.update { map -> map - cloudItem.id }
                    _downloadError.tryEmit(DownloadError.CacheLimitReached)
                    return@launch
                }
                val url = getStreamUrl(cloudItem) ?: run {
                    _itemStates.update { map -> map - cloudItem.id }
                    return@launch
                }
                val downloadedBytes = downloadWithProgress(cloudItem.id, url) { pct ->
                    _itemStates.update { it + (cloudItem.id to ItemDownloadState.InProgress(pct)) }
                }
                if (cloudItem.sizeBytes == null && downloadedBytes > 0) {
                    repo.updateSizeBytes(cloudItem.id, downloadedBytes)
                }
                _itemStates.update { it + (cloudItem.id to ItemDownloadState.Done) }
            } catch (e: CancellationException) {
                _itemStates.update { map -> map - cloudItem.id }
                throw e
            } catch (_: Exception) {
                _itemStates.update { map -> map - cloudItem.id }
            } finally {
                singleDownloadJobs.remove(cloudItem.id)
            }
        }
        singleDownloadJobs[cloudItem.id] = job
    }

    private fun startDownloadAll() {
        downloadJob = viewModelScope.launch {
            val pairs = repo.getItemsWithMetadata(playlistId).first()
            val toDownload = pairs
                .mapNotNull { (_, cloudItem) -> cloudItem }
                .filter { cacheManager.getCacheStatus(it.id, it.sizeBytes) != CacheStatus.CACHED }

            if (toDownload.isEmpty()) return@launch

            val cacheLimit = settingsRepo.cacheLimitBytes.first()

            for (batch in toDownload.chunked(PARALLEL_DOWNLOADS)) {
                if (!isActive) break
                if (cacheManager.simpleCache.cacheSpace >= cacheLimit) {
                    _downloadError.tryEmit(DownloadError.CacheLimitReached)
                    break
                }

                coroutineScope {
                    batch.forEach { cloudItem ->
                        launch(Dispatchers.IO) {
                            _itemStates.update { it + (cloudItem.id to ItemDownloadState.InProgress(null)) }
                            try {
                                val url = getStreamUrl(cloudItem) ?: run {
                                    _itemStates.update { map -> map - cloudItem.id }
                                    return@launch
                                }
                                val downloadedBytes = downloadWithProgress(cloudItem.id, url) { pct ->
                                    _itemStates.update { it + (cloudItem.id to ItemDownloadState.InProgress(pct)) }
                                }
                                if (cloudItem.sizeBytes == null && downloadedBytes > 0) {
                                    repo.updateSizeBytes(cloudItem.id, downloadedBytes)
                                }
                                _itemStates.update { it + (cloudItem.id to ItemDownloadState.Done) }
                            } catch (e: CancellationException) {
                                _itemStates.update { map -> map - cloudItem.id }
                                throw e
                            } catch (_: Exception) {
                                _itemStates.update { map -> map - cloudItem.id }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun downloadWithProgress(
        key: String,
        url: String,
        onProgress: (Float) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val dataSource = CacheDataSource.Factory()
            .setCache(cacheManager.simpleCache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(okHttpClient))
            .createDataSource()

        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setKey(key)
            .build()

        val totalLength = dataSource.open(dataSpec)
        var bytesRead = 0L
        val buffer = ByteArray(128 * 1024)
        try {
            while (isActive) {
                val read = dataSource.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                if (read > 0) {
                    bytesRead += read
                    if (totalLength > 0) onProgress(bytesRead.toFloat() / totalLength)
                }
            }
        } finally {
            dataSource.close()
        }
        if (totalLength > 0) totalLength else bytesRead
    }

    /** Shows a confirmation dialog before removing the track. */
    fun requestRemoveTrack(itemId: String) {
        _pendingRemoveItemId.value = itemId
    }

    /** User confirmed — remove track and clean its cached file if no other playlist uses it. */
    fun confirmRemoveTrack() {
        val itemId = _pendingRemoveItemId.value ?: return
        _pendingRemoveItemId.value = null
        viewModelScope.launch { repo.removeItemAndCleanCache(itemId) }
    }

    fun cancelRemoveTrack() {
        _pendingRemoveItemId.value = null
    }

    companion object {
        private const val PARALLEL_DOWNLOADS = 3
    }
}
