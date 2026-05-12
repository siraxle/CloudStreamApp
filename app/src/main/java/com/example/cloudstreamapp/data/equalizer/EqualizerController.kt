package com.example.cloudstreamapp.data.equalizer

import android.media.audiofx.Equalizer
import androidx.annotation.MainThread
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.C
import com.example.cloudstreamapp.domain.model.EqualizerPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EqualizerController @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private var equalizer: Equalizer? = null

    private val _bandCount = MutableStateFlow(DEFAULT_BAND_COUNT)
    val bandCount: StateFlow<Int> = _bandCount.asStateFlow()

    private val _bandFrequencies = MutableStateFlow(DEFAULT_FREQUENCIES)
    val bandFrequencies: StateFlow<List<Int>> = _bandFrequencies.asStateFlow()

    // Min/max band level in millibels (typically -1500..1500).
    private val _bandLevelRange = MutableStateFlow(-1500 to 1500)
    val bandLevelRange: StateFlow<Pair<Int, Int>> = _bandLevelRange.asStateFlow()

    val isEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.ENABLED] ?: true }

    val preset: Flow<EqualizerPreset> = dataStore.data.map { prefs ->
        val name = prefs[Keys.PRESET]
        EqualizerPreset.entries.firstOrNull { it.name == name } ?: EqualizerPreset.POP
    }

    val bandGains: Flow<List<Short>> = dataStore.data.map { prefs ->
        (0 until DEFAULT_BAND_COUNT).map { band ->
            (prefs[Keys.bandKey(band)] ?: 0).toShort()
        }
    }

    /** Called by PlaybackService on the main thread after the ExoPlayer is built. */
    @MainThread
    fun attach(audioSessionId: Int, scope: CoroutineScope) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        equalizer?.release()
        equalizer = runCatching { Equalizer(0, audioSessionId) }.getOrNull() ?: return

        val eq = equalizer!!
        runCatching {
            _bandCount.value = eq.numberOfBands.toInt()
            _bandFrequencies.value = (0 until eq.numberOfBands).map { band ->
                eq.getBandFreqRange(band.toShort()).average().toInt() / 1000
            }
            val range = eq.bandLevelRange
            _bandLevelRange.value = range[0].toInt() to range[1].toInt()
        }

        scope.launch {
            val prefs = dataStore.data.first()
            val enabled = prefs[Keys.ENABLED] ?: true
            withContext(Dispatchers.Main) { runCatching { eq.enabled = enabled } }
            repeat(eq.numberOfBands.toInt()) { band ->
                val gain = (prefs[Keys.bandKey(band)] ?: 0).toShort()
                withContext(Dispatchers.Main) {
                    runCatching { eq.setBandLevel(band.toShort(), gain) }
                }
            }
        }
    }

    @MainThread
    fun release() {
        equalizer?.release()
        equalizer = null
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ENABLED] = enabled }
        withContext(Dispatchers.Main) { runCatching { equalizer?.enabled = enabled } }
    }

    suspend fun setBandGain(band: Int, gainMb: Short) {
        dataStore.edit { prefs ->
            prefs[Keys.bandKey(band)] = gainMb.toInt()
            prefs[Keys.PRESET] = EqualizerPreset.CUSTOM.name
        }
        withContext(Dispatchers.Main) {
            runCatching { equalizer?.setBandLevel(band.toShort(), gainMb) }
        }
    }

    suspend fun setPreset(preset: EqualizerPreset) {
        dataStore.edit { prefs ->
            prefs[Keys.PRESET] = preset.name
            preset.bands.forEachIndexed { band, gain ->
                prefs[Keys.bandKey(band)] = gain.toInt()
            }
        }
        withContext(Dispatchers.Main) {
            val eq = equalizer ?: return@withContext
            preset.bands.forEachIndexed { band, gain ->
                runCatching { eq.setBandLevel(band.toShort(), gain) }
            }
        }
    }

    private object Keys {
        val ENABLED = booleanPreferencesKey("eq_enabled")
        val PRESET = stringPreferencesKey("eq_preset")
        fun bandKey(band: Int) = intPreferencesKey("eq_band_$band")
    }

    companion object {
        const val DEFAULT_BAND_COUNT = 5
        val DEFAULT_FREQUENCIES = listOf(60, 230, 910, 3600, 14000)
    }
}
