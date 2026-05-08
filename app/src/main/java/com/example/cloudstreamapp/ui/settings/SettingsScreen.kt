package com.example.cloudstreamapp.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private val CACHE_PRESETS = listOf(
    500L * 1024 * 1024 to "500 MB",
    1L * 1024 * 1024 * 1024 to "1 GB",
    2L * 1024 * 1024 * 1024 to "2 GB",
    5L * 1024 * 1024 * 1024 to "5 GB",
)

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private val WarningOrange = Color(0xFFF57C00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val cacheLimitBytes by viewModel.cacheLimitBytes.collectAsState()
    val usedCacheBytes by viewModel.usedCacheBytes.collectAsState()
    val wifiOnly by viewModel.wifiOnlyPrefetch.collectAsState()
    val speed by viewModel.playbackSpeed.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshCacheUsage()
    }

    val usedFraction = if (cacheLimitBytes > 0) (usedCacheBytes.toFloat() / cacheLimitBytes).coerceIn(0f, 1f) else 0f
    val isNearLimit = usedFraction >= 0.8f
    val isCritical = usedFraction >= 0.95f

    Scaffold(
        topBar = { TopAppBar(title = { Text("Настройки") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("Кэш")

            ListItem(
                headlineContent = { Text("Лимит кэша медиа") },
                supportingContent = {
                    Column {
                        val label = CACHE_PRESETS.minByOrNull { kotlin.math.abs(it.first - cacheLimitBytes) }?.second ?: "Custom"
                        Text(label)
                        Row(modifier = Modifier.fillMaxWidth()) {
                            CACHE_PRESETS.forEach { (bytes, label) ->
                                Button(
                                    onClick = { viewModel.setCacheLimit(bytes) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 4.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            )

            ListItem(
                headlineContent = { Text("Использование кэша") },
                supportingContent = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${formatBytes(usedCacheBytes)} / ${formatBytes(cacheLimitBytes)}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "${(usedFraction * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isNearLimit) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    isCritical -> MaterialTheme.colorScheme.error
                                    isNearLimit -> WarningOrange
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { usedFraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = when {
                                isCritical -> MaterialTheme.colorScheme.error
                                isNearLimit -> WarningOrange
                                else -> MaterialTheme.colorScheme.primary
                            },
                        )
                        if (isNearLimit) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (isCritical)
                                    "Кэш почти заполнен. Очистите место или увеличьте лимит."
                                else
                                    "Кэш заполнен на ${(usedFraction * 100).toInt()}%. Рекомендуем очистить или увеличить лимит.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCritical) MaterialTheme.colorScheme.error else WarningOrange,
                            )
                        }
                    }
                }
            )

            ListItem(
                headlineContent = { Text("Предзагрузка только по WiFi") },
                trailingContent = {
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = { viewModel.setWifiOnlyPrefetch(it) },
                    )
                },
            )

            ListItem(
                headlineContent = { Text("Очистить кэш медиа") },
                trailingContent = {
                    Button(onClick = { viewModel.clearCache() }) { Text("Очистить") }
                },
            )

            ListItem(
                headlineContent = { Text("Очистить кэш изображений") },
                supportingContent = { Text("Coil disk + memory cache") },
                trailingContent = {
                    Button(onClick = { viewModel.clearImageCache() }) { Text("Очистить") }
                },
            )

            SectionHeader("Воспроизведение")

            ListItem(
                headlineContent = { Text("Скорость: ${speed}×") },
                supportingContent = {
                    Slider(
                        value = SPEED_OPTIONS.indexOf(speed).toFloat().coerceAtLeast(0f),
                        onValueChange = { idx ->
                            val i = idx.toInt().coerceIn(SPEED_OPTIONS.indices)
                            viewModel.setPlaybackSpeed(SPEED_OPTIONS[i])
                        },
                        valueRange = 0f..(SPEED_OPTIONS.size - 1).toFloat(),
                        steps = SPEED_OPTIONS.size - 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}
