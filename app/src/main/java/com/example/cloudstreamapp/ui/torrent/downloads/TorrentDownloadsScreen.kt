package com.example.cloudstreamapp.ui.torrent.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.cloudstreamapp.core.utils.toHumanReadableSize
import com.example.cloudstreamapp.data.torrent.download.TorrentDownloadEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentDownloadsScreen(
    onBack: () -> Unit,
    onPlayTrack: (TorrentDownloadEntity) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    viewModel: TorrentDownloadsViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsState()
    val pendingDelete by viewModel.pendingDelete.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TorrentDownloadsViewModel.Event.PlayTrack -> onPlayTrack(event.entity)
                is TorrentDownloadsViewModel.Event.OpenPlaylist -> onOpenPlaylist(event.playlistId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Скачанные треки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        }
    ) { padding ->
        if (groups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text("Нет скачанных треков", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Нажмите ↓ рядом с файлом или папкой в торрент-браузере",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                groups.forEach { group ->
                    // Group header
                    item(key = "header:${group.torrentName}") {
                        GroupHeader(
                            group = group,
                            onCreatePlaylist = { viewModel.createPlaylist(group) },
                            onDeleteGroup = { viewModel.deleteGroup(group) },
                        )
                    }

                    // Track items
                    items(group.tracks, key = { "track:${it.id}" }) { entity ->
                        TrackItem(
                            entity = entity,
                            onPlay = { viewModel.playTrack(entity) },
                            onDelete = { viewModel.requestDelete(entity) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
    }

    if (pendingDelete != null) {
        val entity = pendingDelete!!
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text("Удалить файл?") },
            text = {
                Text(
                    "«${entity.fileName}» будет удалён с устройства. " +
                    "Плейлисты, в которые он добавлен, останутся, но трек перестанет воспроизводиться."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun GroupHeader(
    group: TorrentDownloadsViewModel.DownloadGroup,
    onCreatePlaylist: () -> Unit,
    onDeleteGroup: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.torrentName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${pluralTracks(group.tracks.size)} · ${group.totalSizeBytes.toHumanReadableSize()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCreatePlaylist) {
                Icon(
                    Icons.Default.PlaylistAdd,
                    contentDescription = "Создать плейлист",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onDeleteGroup) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Удалить все треки",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun TrackItem(
    entity: TorrentDownloadEntity,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onPlay),
        leadingContent = {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = {
            Text(entity.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = entity.sizeBytes.takeIf { it > 0 }?.let { size ->
            { Text(size.toHumanReadableSize(), style = MaterialTheme.typography.bodySmall) }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Воспроизвести",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Удалить",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(),
    )
}

private fun pluralTracks(count: Int): String = when {
    count % 100 in 11..19 -> "$count треков"
    count % 10 == 1 -> "$count трек"
    count % 10 in 2..4 -> "$count трека"
    else -> "$count треков"
}
