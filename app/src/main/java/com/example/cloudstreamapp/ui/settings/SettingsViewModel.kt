package com.example.cloudstreamapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.core.cache.ImageCacheManager
import com.example.cloudstreamapp.core.cache.MediaCacheManager
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
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
) : ViewModel() {

    val cacheLimitBytes: StateFlow<Long> = settings.cacheLimitBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaCacheManager.DEFAULT_MAX_BYTES)

    private val _usedCacheBytes = MutableStateFlow(0L)
    val usedCacheBytes: StateFlow<Long> = _usedCacheBytes.asStateFlow()

    init {
        refreshCacheUsage()
    }

    fun refreshCacheUsage() {
        _usedCacheBytes.value = cacheManager.usedBytes
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
        _usedCacheBytes.value = cacheManager.usedBytes
    }

    fun clearImageCache() {
        imageCacheManager.clearAll()
    }
}
