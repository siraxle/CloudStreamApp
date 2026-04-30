package com.example.cloudstreamapp.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.example.cloudstreamapp.domain.port.SettingsRepositoryPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepositoryPort {

    override val cacheLimitBytes: Flow<Long> = dataStore.data
        .map { it[Keys.CACHE_LIMIT_BYTES] ?: 2L * 1024 * 1024 * 1024 }

    override val wifiOnlyPrefetch: Flow<Boolean> = dataStore.data
        .map { it[Keys.WIFI_ONLY_PREFETCH] ?: false }

    override val playbackSpeed: Flow<Float> = dataStore.data
        .map { it[Keys.PLAYBACK_SPEED] ?: 1.0f }

    override val crossfadeDurationMs: Flow<Int> = dataStore.data
        .map { it[Keys.CROSSFADE_DURATION_MS] ?: 0 }

    override suspend fun setCacheLimitBytes(bytes: Long) {
        dataStore.edit { it[Keys.CACHE_LIMIT_BYTES] = bytes }
    }

    override suspend fun setWifiOnlyPrefetch(enabled: Boolean) {
        dataStore.edit { it[Keys.WIFI_ONLY_PREFETCH] = enabled }
    }

    override suspend fun setPlaybackSpeed(speed: Float) {
        dataStore.edit { it[Keys.PLAYBACK_SPEED] = speed }
    }

    override suspend fun setCrossfadeDurationMs(ms: Int) {
        dataStore.edit { it[Keys.CROSSFADE_DURATION_MS] = ms }
    }

    private object Keys {
        val CACHE_LIMIT_BYTES = longPreferencesKey("cache_limit_bytes")
        val WIFI_ONLY_PREFETCH = booleanPreferencesKey("wifi_only_prefetch")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val CROSSFADE_DURATION_MS = intPreferencesKey("crossfade_duration_ms")
    }
}
