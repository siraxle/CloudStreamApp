package com.example.cloudstreamapp.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.data.equalizer.EqualizerController
import com.example.cloudstreamapp.domain.model.EqualizerPreset
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val controller: EqualizerController,
) : ViewModel() {

    val isEnabled: StateFlow<Boolean> = controller.isEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val preset: StateFlow<EqualizerPreset> = controller.preset
        .stateIn(viewModelScope, SharingStarted.Eagerly, EqualizerPreset.FLAT)

    val bandGains: StateFlow<List<Short>> = controller.bandGains
        .stateIn(viewModelScope, SharingStarted.Eagerly, List(EqualizerController.DEFAULT_BAND_COUNT) { 0.toShort() })

    val bandFrequencies: StateFlow<List<Int>> = controller.bandFrequencies
    val bandLevelRange: StateFlow<Pair<Int, Int>> = controller.bandLevelRange

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        controller.setEnabled(enabled)
    }

    fun setBandGain(band: Int, gainMb: Short) = viewModelScope.launch {
        controller.setBandGain(band, gainMb)
    }

    fun setPreset(preset: EqualizerPreset) = viewModelScope.launch {
        controller.setPreset(preset)
    }
}
