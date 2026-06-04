package com.example.cloudstreamapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.core.cache.ImageCacheManager
import com.example.cloudstreamapp.core.cache.MediaCacheManager
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.data.torrent.auth.TorrentAuthStore
import com.example.cloudstreamapp.data.torrent.download.TorrentCacheManager
import com.example.cloudstreamapp.data.torrent.download.TorrentDownloadManager
import com.example.cloudstreamapp.data.torrent.provider.TorrentProviderConfig
import com.example.cloudstreamapp.data.torrent.provider.TorrentSource
import com.example.cloudstreamapp.domain.port.SettingsRepositoryPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepositoryPort,
    private val cacheManager: MediaCacheManager,
    private val imageCacheManager: ImageCacheManager,
    private val playlistRepo: PlaylistRepositoryImpl,
    private val torrentProviderConfig: TorrentProviderConfig,
    private val torrentAuthStore: TorrentAuthStore,
    private val torrentDownloadManager: TorrentDownloadManager,
    private val torrentCacheManager: TorrentCacheManager,
) : ViewModel() {

    // ── Storage indicators ────────────────────────────────────────────────────

    /** Files explicitly saved to device (external Music dir). */
    private val _downloadedBytes = MutableStateFlow(0L)
    val downloadedBytes: StateFlow<Long> = _downloadedBytes.asStateFlow()

    /** Torrent streaming cache in cacheDir/torrents. */
    private val _torrentCacheBytes = MutableStateFlow(0L)
    val torrentCacheBytes: StateFlow<Long> = _torrentCacheBytes.asStateFlow()

    /** ExoPlayer permanent cloud cache in filesDir. */
    private val _cloudCacheBytes = MutableStateFlow(0L)
    val cloudCacheBytes: StateFlow<Long> = _cloudCacheBytes.asStateFlow()

    /** ExoPlayer temporary streaming buffer (cleared on every app start). */
    private val _tempCacheBytes = MutableStateFlow(0L)
    val tempCacheBytes: StateFlow<Long> = _tempCacheBytes.asStateFlow()

    val wifiOnlyPrefetch: StateFlow<Boolean> = settings.wifiOnlyPrefetch
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ── Auth dialog state ─────────────────────────────────────────────────────

    private val _pendingAuthSource = MutableStateFlow<TorrentSource?>(null)
    val pendingAuthSource: StateFlow<TorrentSource?> = _pendingAuthSource.asStateFlow()

    private val _loginInProgress = MutableStateFlow(false)
    val loginInProgress: StateFlow<Boolean> = _loginInProgress.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    init {
        refreshStorageUsage()
    }

    fun refreshStorageUsage() {
        _cloudCacheBytes.value = cacheManager.permUsedBytes
        _tempCacheBytes.value = cacheManager.tempUsedBytes
        viewModelScope.launch {
            _downloadedBytes.value = withContext(Dispatchers.IO) {
                torrentDownloadManager.downloadDir
                    .takeIf { it.exists() }
                    ?.walkTopDown()
                    ?.filter { it.isFile }
                    ?.sumOf { it.length() }
                    ?: 0L
            }
            _torrentCacheBytes.value = withContext(Dispatchers.IO) {
                torrentCacheManager.totalCacheSizeBytes()
            }
        }
    }

    // ── Storage clear actions ─────────────────────────────────────────────────

    fun clearDownloadedFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            torrentDownloadManager.deleteAllDownloads()
            _downloadedBytes.value = 0L
        }
    }

    fun clearTorrentCache() {
        viewModelScope.launch(Dispatchers.IO) {
            torrentCacheManager.clearAllCache()
            _torrentCacheBytes.value = 0L
        }
    }

    fun clearCloudCache() {
        cacheManager.clearAll()
        playlistRepo.onCacheCleared()
        _cloudCacheBytes.value = 0L
    }

    fun clearTempCache() {
        cacheManager.clearTemp()
        _tempCacheBytes.value = 0L
    }

    fun clearImageCache() {
        imageCacheManager.clearAll()
    }

    fun clearAllCaches() {
        clearDownloadedFiles()
        clearTorrentCache()
        clearCloudCache()
    }

    fun setWifiOnlyPrefetch(enabled: Boolean) {
        viewModelScope.launch { settings.setWifiOnlyPrefetch(enabled) }
    }

    // ── Torrent source management ─────────────────────────────────────────────

    fun torrentSourceEnabled(source: TorrentSource): StateFlow<Boolean> =
        torrentProviderConfig.isEnabled(source)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun isAuthenticated(source: TorrentSource): StateFlow<Boolean> =
        torrentAuthStore.isAuthenticated(source)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onTorrentSourceToggle(source: TorrentSource, enabled: Boolean) {
        if (enabled && source.requiresAuth) {
            viewModelScope.launch {
                val alreadyAuthenticated = torrentAuthStore.isAuthenticated(source).first()
                if (alreadyAuthenticated) {
                    torrentProviderConfig.setEnabled(source, true)
                } else {
                    _pendingAuthSource.value = source
                }
            }
        } else {
            viewModelScope.launch { torrentProviderConfig.setEnabled(source, enabled) }
        }
    }

    fun login(source: TorrentSource, username: String, password: String) {
        _loginError.value = null
        _loginInProgress.value = true
        viewModelScope.launch {
            val result = Result.failure<Unit>(UnsupportedOperationException("Auth not supported for ${source.displayName}"))
            _loginInProgress.value = false
            if (result.isSuccess) {
                torrentAuthStore.saveAuth(source, username, password)
                torrentProviderConfig.setEnabled(source, true)
                _pendingAuthSource.value = null
            } else {
                _loginError.value = result.exceptionOrNull()?.message ?: "Ошибка входа"
            }
        }
    }

    fun dismissAuthDialog() {
        _pendingAuthSource.value = null
        _loginError.value = null
    }

    fun logout(source: TorrentSource) {
        viewModelScope.launch {
            torrentAuthStore.clearAuth(source)
            torrentProviderConfig.setEnabled(source, false)
        }
    }
}
