package com.example.cloudstreamapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.core.cache.MediaCacheManager
import com.example.cloudstreamapp.domain.port.SettingsRepositoryPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepositoryPort,
    private val cacheManager: MediaCacheManager,
) : ViewModel() {

    val cacheLimitBytes: StateFlow<Long> = settings.cacheLimitBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaCacheManager.DEFAULT_MAX_BYTES)

    val wifiOnlyPrefetch: StateFlow<Boolean> = settings.wifiOnlyPrefetch
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val playbackSpeed: StateFlow<Float> = settings.playbackSpeed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1.0f)

    fun setCacheLimit(bytes: Long) {
        viewModelScope.launch { settings.setCacheLimitBytes(bytes) }
    }

    fun setWifiOnlyPrefetch(enabled: Boolean) {
        viewModelScope.launch { settings.setWifiOnlyPrefetch(enabled) }
    }

    fun setPlaybackSpeed(speed: Float) {
        viewModelScope.launch { settings.setPlaybackSpeed(speed) }
    }

    fun clearCache() {
        cacheManager.release()
    }
}
