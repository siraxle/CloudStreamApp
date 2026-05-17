package com.example.cloudstreamapp.data.torrent.provider

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorrentProviderConfig @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private fun keyFor(source: TorrentSource) =
        booleanPreferencesKey("torrent_provider_${source.name.lowercase()}")

    fun isEnabled(source: TorrentSource): Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[keyFor(source)] ?: false }

    suspend fun setEnabled(source: TorrentSource, enabled: Boolean) {
        dataStore.edit { prefs -> prefs[keyFor(source)] = enabled }
    }
}
