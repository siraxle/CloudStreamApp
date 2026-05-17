package com.example.cloudstreamapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.core.cache.ImageCacheManager
import com.example.cloudstreamapp.core.cache.MediaCacheManager
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.data.torrent.provider.TorrentProviderConfig
import com.example.cloudstreamapp.data.torrent.provider.TorrentSource
import com.example.cloudstreamapp.domain.port.SettingsRepositoryPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepositoryPort,
    private val cacheManager: MediaCacheManager,
    private val imageCacheManager: ImageCacheManager,
    private val playlistRepo: PlaylistRepositoryImpl,
    private val torrentProviderConfig: TorrentProviderConfig,
) : ViewModel() {

    val cacheLimitBytes: StateFlow<Long> = settings.cacheLimitBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaCacheManager.DEFAULT_MAX_BYTES)

    private val _usedCacheBytes = MutableStateFlow(0L)
    val usedCacheBytes: StateFlow<Long> = _usedCacheBytes.asStateFlow()

    private val _tempUsedCacheBytes = MutableStateFlow(0L)
    val tempUsedCacheBytes: StateFlow<Long> = _tempUsedCacheBytes.asStateFlow()

    init {
        refreshCacheUsage()
    }

    fun refreshCacheUsage() {
        _usedCacheBytes.value = cacheManager.permUsedBytes
        _tempUsedCacheBytes.value = cacheManager.tempUsedBytes
    }

    val wifiOnlyPrefetch: StateFlow<Boolean> = settings.wifiOnlyPrefetch
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setCacheLimit(bytes: Long) {
        viewModelScope.launch { settings.setCacheLimitBytes(bytes) }
    }

    fun setWifiOnlyPrefetch(enabled: Boolean) {
        viewModelScope.launch { settings.setWifiOnlyPrefetch(enabled) }
    }

    fun clearCache() {
        cacheManager.clearAll()
        playlistRepo.onCacheCleared()
        _usedCacheBytes.value = 0L
    }

    fun clearTempCache() {
        cacheManager.clearTemp()
        _tempUsedCacheBytes.value = 0L
    }

    fun clearImageCache() {
        imageCacheManager.clearAll()
    }

    fun torrentSourceEnabled(source: TorrentSource): StateFlow<Boolean> =
        torrentProviderConfig.isEnabled(source)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setTorrentSourceEnabled(source: TorrentSource, enabled: Boolean) {
        viewModelScope.launch { torrentProviderConfig.setEnabled(source, enabled) }
    }
}
