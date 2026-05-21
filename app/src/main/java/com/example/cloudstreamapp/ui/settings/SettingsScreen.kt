package com.example.cloudstreamapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.cloudstreamapp.core.cache.MediaCacheManager
import com.example.cloudstreamapp.data.torrent.provider.TorrentSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val downloadedBytes by viewModel.downloadedBytes.collectAsState()
    val torrentCacheBytes by viewModel.torrentCacheBytes.collectAsState()
    val cloudCacheBytes by viewModel.cloudCacheBytes.collectAsState()
    val tempCacheBytes by viewModel.tempCacheBytes.collectAsState()
    val wifiOnly by viewModel.wifiOnlyPrefetch.collectAsState()
    val pendingAuthSource by viewModel.pendingAuthSource.collectAsState()
    val loginInProgress by viewModel.loginInProgress.collectAsState()
    val loginError by viewModel.loginError.collectAsState()

    var clearTarget by remember { mutableStateOf<ClearTarget?>(null) }

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
            viewModel.refreshStorageUsage()
        }
    }

    clearTarget?.let { target ->
        ClearConfirmDialog(
            target = target,
            onConfirm = {
                when (target) {
                    ClearTarget.Downloads    -> viewModel.clearDownloadedFiles()
                    ClearTarget.TorrentCache -> viewModel.clearTorrentCache()
                    ClearTarget.CloudCache   -> viewModel.clearCloudCache()
                    ClearTarget.All          -> viewModel.clearAllCaches()
                }
                clearTarget = null
            },
            onDismiss = { clearTarget = null },
        )
    }

    val totalBytes = downloadedBytes + torrentCacheBytes + cloudCacheBytes
    val tempFraction = (tempCacheBytes.toFloat() / MediaCacheManager.DEFAULT_TEMP_MAX_BYTES).coerceIn(0f, 1f)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Настройки") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("Использование памяти")

            StorageRow(
                label = "Скачанные файлы",
                description = "Сохранено на устройство",
                bytes = downloadedBytes,
                onClear = { clearTarget = ClearTarget.Downloads },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))

            StorageRow(
                label = "Кэш торрентов",
                description = "Стриминг-кэш в памяти приложения",
                bytes = torrentCacheBytes,
                onClear = { clearTarget = ClearTarget.TorrentCache },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))

            StorageRow(
                label = "Кэш облака",
                description = "Предзагруженные треки из облачных источников",
                bytes = cloudCacheBytes,
                onClear = { clearTarget = ClearTarget.CloudCache },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))

            ListItem(
                headlineContent = {
                    Text(
                        "Итого: ${formatBytes(totalBytes)}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                },
                trailingContent = {
                    OutlinedButton(
                        onClick = { clearTarget = ClearTarget.All },
                        enabled = totalBytes > 0,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Очистить всё") }
                },
            )

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
                                text = "${formatBytes(tempCacheBytes)} / ${formatBytes(MediaCacheManager.DEFAULT_TEMP_MAX_BYTES)}",
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
                        enabled = tempCacheBytes > 0,
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

private enum class ClearTarget { Downloads, TorrentCache, CloudCache, All }

@Composable
private fun StorageRow(
    label: String,
    description: String,
    bytes: Long,
    onClear: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Column {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatBytes(bytes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        trailingContent = {
            Button(
                onClick = onClear,
                enabled = bytes > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text("Очистить") }
        },
    )
}

@Composable
private fun ClearConfirmDialog(
    target: ClearTarget,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, text) = when (target) {
        ClearTarget.Downloads -> "Удалить скачанные файлы?" to
            "Все файлы, сохранённые на устройство, будут удалены. Плейлисты сохранятся, но треки потребуют повторного скачивания."
        ClearTarget.TorrentCache -> "Очистить торрент-кэш?" to
            "Кэшированные стриминг-файлы будут удалены. При следующем воспроизведении треки будут загружены заново."
        ClearTarget.CloudCache -> "Очистить кэш облака?" to
            "Предзагруженные треки из облачных источников будут удалены. Плейлисты сохранятся."
        ClearTarget.All -> "Очистить всё?" to
            "Будут удалены все скачанные файлы, торрент-кэш и кэш облака. Плейлисты сохранятся."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Очистить", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
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
