package com.example.cloudstreamapp.ui.player

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.cloudstreamapp.domain.model.EqualizerPreset
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerBottomSheet(
    onDismiss: () -> Unit,
    viewModel: EqualizerViewModel = hiltViewModel(),
) {
    val isEnabled by viewModel.isEnabled.collectAsState()
    val preset by viewModel.preset.collectAsState()
    val bandGains by viewModel.bandGains.collectAsState()
    val bandFrequencies by viewModel.bandFrequencies.collectAsState()
    val (minLevel, maxLevel) = viewModel.bandLevelRange.collectAsState().value

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Эквалайзер", style = MaterialTheme.typography.titleLarge)
                Switch(
                    checked = isEnabled,
                    onCheckedChange = viewModel::setEnabled,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Preset chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EqualizerPreset.entries
                    .filter { it != EqualizerPreset.CUSTOM }
                    .forEach { p ->
                        FilterChip(
                            selected = preset == p,
                            onClick = { viewModel.setPreset(p) },
                            label = { Text(p.displayName) },
                            enabled = isEnabled,
                        )
                    }
                if (preset == EqualizerPreset.CUSTOM) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text(EqualizerPreset.CUSTOM.displayName) },
                        enabled = false,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Band sliders
            bandGains.forEachIndexed { band, gainMb ->
                val freqLabel = bandFrequencies.getOrNull(band)?.let { formatFrequency(it) } ?: "Band $band"
                val gainDb = gainMb / 100f

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = freqLabel,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(52.dp),
                        textAlign = TextAlign.End,
                    )
                    Slider(
                        value = gainMb.toFloat(),
                        onValueChange = { viewModel.setBandGain(band, it.roundToInt().toShort()) },
                        valueRange = minLevel.toFloat()..maxLevel.toFloat(),
                        enabled = isEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )
                    Text(
                        text = formatGain(gainDb),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(44.dp),
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}

private fun formatFrequency(hz: Int): String = when {
    hz >= 1000 -> "${hz / 1000} кГц"
    else -> "$hz Гц"
}

private fun formatGain(db: Float): String {
    val rounded = (db * 10).roundToInt() / 10f
    return if (rounded >= 0f) "+${"%.1f".format(rounded)}" else "${"%.1f".format(rounded)}"
}
