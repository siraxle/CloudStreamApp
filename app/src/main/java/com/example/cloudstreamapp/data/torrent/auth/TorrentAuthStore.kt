package com.example.cloudstreamapp.data.torrent.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.cloudstreamapp.data.torrent.provider.TorrentSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorrentAuthStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private fun usernameKey(source: TorrentSource) =
        stringPreferencesKey("auth_user_${source.name}")

    private fun passwordKey(source: TorrentSource) =
        stringPreferencesKey("auth_pass_${source.name}")

    private fun authenticatedKey(source: TorrentSource) =
        booleanPreferencesKey("auth_ok_${source.name}")

    fun isAuthenticated(source: TorrentSource): Flow<Boolean> =
        dataStore.data.map { it[authenticatedKey(source)] ?: false }

    suspend fun getCredentials(source: TorrentSource): Pair<String, String>? {
        val prefs = dataStore.data.first()
        val u = prefs[usernameKey(source)] ?: return null
        val p = prefs[passwordKey(source)] ?: return null
        return u to p
    }

    suspend fun saveAuth(source: TorrentSource, username: String, password: String) {
        dataStore.edit { prefs ->
            prefs[usernameKey(source)] = username
            prefs[passwordKey(source)] = password
            prefs[authenticatedKey(source)] = true
        }
    }

    suspend fun clearAuth(source: TorrentSource) {
        dataStore.edit { prefs ->
            prefs.remove(usernameKey(source))
            prefs.remove(passwordKey(source))
            prefs[authenticatedKey(source)] = false
        }
    }
}
