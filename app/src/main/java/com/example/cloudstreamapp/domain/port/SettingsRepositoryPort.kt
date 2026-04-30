package com.example.cloudstreamapp.domain.port

import kotlinx.coroutines.flow.Flow

interface SettingsRepositoryPort {
    val cacheLimitBytes: Flow<Long>
    val wifiOnlyPrefetch: Flow<Boolean>
    val playbackSpeed: Flow<Float>
    val crossfadeDurationMs: Flow<Int>

    suspend fun setCacheLimitBytes(bytes: Long)
    suspend fun setWifiOnlyPrefetch(enabled: Boolean)
    suspend fun setPlaybackSpeed(speed: Float)
    suspend fun setCrossfadeDurationMs(ms: Int)
}
