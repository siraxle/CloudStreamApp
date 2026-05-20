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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.cloudstreamapp.core.cache.MediaCacheManager
import com.example.cloudstreamapp.data.torrent.provider.TorrentSource

private val CACHE_PRESETS = listOf(
    500L * 1024 * 1024 to "500 MB",
    1L * 1024 * 1024 * 1024 to "1 GB",
    2L * 1024 * 1024 * 1024 to "2 GB",
    5L * 1024 * 1024 * 1024 to "5 GB",
)

private val WarningOrange = Color(0xFFF57C00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val cacheLimitBytes by viewModel.cacheLimitBytes.collectAsState()
    val usedCacheBytes by viewModel.usedCacheBytes.collectAsState()
    val tempUsedCacheBytes by viewModel.tempUsedCacheBytes.collectAsState()
    val wifiOnly by viewModel.wifiOnlyPrefetch.collectAsState()
    var showClearCacheDialog by remember { mutableStateOf(false) }
    val pendingAuthSource by viewModel.pendingAuthSource.collectAsState()
    val loginInProgress by viewModel.loginInProgress.collectAsState()
    val loginError by viewModel.loginError.collectAsState()

    pendingAuthSource?.let { source ->
        TrackerAuthDialog(
            source = source,
            loginInProgress = loginInProgress,
            loginError = loginError,
            onLogin = { u, p -> viewModel.login(source, u, p) },
            onDismiss = viewModel::dismissAuthDialog,
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshCacheUsage()
        }
    }

    val usedFraction = if (cacheLimitBytes > 0) (usedCacheBytes.toFloat() / cacheLimitBytes).coerceIn(0f, 1f) else 0f
    val isNearLimit = usedFraction >= 0.8f
    val isCritical = usedFraction >= 0.95f

    val tempFraction = (tempUsedCacheBytes.toFloat() / MediaCacheManager.DEFAULT_TEMP_MAX_BYTES).coerceIn(0f, 1f)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Настройки") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("Кэш загрузок")

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
                                    enabled = usedCacheBytes <= bytes,
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
                headlineContent = { Text("Использование загрузок") },
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
                headlineContent = { Text("Очистить кэш загрузок") },
                trailingContent = {
                    Button(onClick = { showClearCacheDialog = true }) { Text("Очистить") }
                },
            )

            if (showClearCacheDialog) {
                AlertDialog(
                    onDismissRequest = { showClearCacheDialog = false },
                    title = { Text("Очистить кэш?") },
                    text = {
                        Text(
                            "Все скачанные треки будут удалены с устройства. " +
                                "Плейлисты сохранятся, но треки нужно будет скачать заново."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.clearCache()
                            showClearCacheDialog = false
                        }) {
                            Text("Очистить", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearCacheDialog = false }) { Text("Отмена") }
                    },
                )
            }

            SectionHeader("Буфер воспроизведения")

            ListItem(
                headlineContent = { Text("Временный кэш") },
                supportingContent = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Используется при стриминге. Очищается при каждом запуске приложения.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${formatBytes(tempUsedCacheBytes)} / ${formatBytes(MediaCacheManager.DEFAULT_TEMP_MAX_BYTES)}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "${(tempFraction * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { tempFraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                trailingContent = {
                    Button(
                        onClick = { viewModel.clearTempCache() },
                        enabled = tempUsedCacheBytes > 0,
                    ) { Text("Очистить") }
                },
            )

            SectionHeader("Прочее")

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
                headlineContent = { Text("Очистить кэш изображений") },
                supportingContent = { Text("Coil disk + memory cache") },
                trailingContent = {
                    Button(onClick = { viewModel.clearImageCache() }) { Text("Очистить") }
                },
            )

            SectionHeader("Торрент-источники")

            TorrentSource.entries.forEach { source ->
                val enabledFlow = remember(source) { viewModel.torrentSourceEnabled(source) }
                val enabled by enabledFlow.collectAsState()

                if (source.requiresAuth) {
                    val authenticatedFlow = remember(source) { viewModel.isAuthenticated(source) }
                    val authenticated by authenticatedFlow.collectAsState()
                    AuthRequiredSourceItem(
                        source = source,
                        enabled = enabled,
                        authenticated = authenticated,
                        onToggle = { viewModel.onTorrentSourceToggle(source, it) },
                        onLogout = { viewModel.logout(source) },
                    )
                } else {
                    ListItem(
                        headlineContent = { Text(source.displayName) },
                        trailingContent = {
                            Switch(
                                checked = enabled,
                                onCheckedChange = { viewModel.onTorrentSourceToggle(source, it) },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthRequiredSourceItem(
    source: TorrentSource,
    enabled: Boolean,
    authenticated: Boolean,
    onToggle: (Boolean) -> Unit,
    onLogout: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(source.displayName) },
        supportingContent = {
            Text(if (authenticated) "Авторизован" else "Требуется вход")
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (authenticated) {
                    TextButton(onClick = onLogout) { Text("Выйти") }
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}
