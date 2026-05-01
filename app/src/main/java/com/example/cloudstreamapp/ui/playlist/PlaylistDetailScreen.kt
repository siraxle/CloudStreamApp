package com.example.cloudstreamapp.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.cloudstreamapp.core.utils.isAudioFile
import com.example.cloudstreamapp.core.utils.isVideoFile
import com.example.cloudstreamapp.domain.model.CacheStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onPlayTrack: (index: Int) -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val tracks by viewModel.tracks.collectAsState()
    val name by viewModel.playlistName.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = {
                    Column {
                        Text(name)
                        if (isDownloading) {
                            Text(
                                text = "Загрузка на устройство…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.triggerDownload() }) {
                        Icon(
                            Icons.Default.DownloadForOffline,
                            contentDescription = "Скачать все на устройство",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (tracks.isEmpty()) {
                Text(
                    text = "Плейлист пуст",
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Column {
                    if (isDownloading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    LazyColumn {
                        itemsIndexed(tracks, key = { _, row -> row.item.id }) { index, row ->
                            val cloudItem = row.cloudItem
                            val displayName = cloudItem?.name ?: "Неизвестный трек"
                            val icon = when {
                                cloudItem == null -> Icons.AutoMirrored.Filled.InsertDriveFile
                                cloudItem.name.isAudioFile() -> Icons.Default.AudioFile
                                cloudItem.name.isVideoFile() -> Icons.Default.VideoFile
                                else -> Icons.AutoMirrored.Filled.InsertDriveFile
                            }
                            val iconTint = when (cloudItem?.cacheStatus) {
                                CacheStatus.CACHED -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val supportingText = when (cloudItem?.cacheStatus) {
                                CacheStatus.CACHED -> "На устройстве"
                                CacheStatus.PARTIAL -> "Загружается..."
                                else -> cloudItem?.sizeBytes?.let { bytes ->
                                    when {
                                        bytes >= 1024L * 1024 -> "${bytes / 1024 / 1024} МБ"
                                        bytes >= 1024 -> "${bytes / 1024} КБ"
                                        else -> null
                                    }
                                }
                            }
                            val supportingColor = when (cloudItem?.cacheStatus) {
                                CacheStatus.CACHED -> MaterialTheme.colorScheme.tertiary
                                CacheStatus.PARTIAL -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            ListItem(
                                headlineContent = {
                                    Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = supportingText?.let { text ->
                                    {
                                        Text(
                                            text = text,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = supportingColor,
                                        )
                                    }
                                },
                                leadingContent = {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp),
                                        tint = iconTint,
                                    )
                                },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        when (cloudItem?.cacheStatus) {
                                            CacheStatus.CACHED -> Icon(
                                                imageVector = Icons.Default.OfflinePin,
                                                contentDescription = "На устройстве",
                                                modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.tertiary,
                                            )
                                            CacheStatus.PARTIAL -> Icon(
                                                imageVector = Icons.Default.Downloading,
                                                contentDescription = "Загружается",
                                                modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                            else -> Unit
                                        }
                                        IconButton(onClick = { viewModel.removeTrack(row.item.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Удалить из плейлиста")
                                        }
                                    }
                                },
                                modifier = Modifier.clickable(enabled = cloudItem != null) {
                                    onPlayTrack(index)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
