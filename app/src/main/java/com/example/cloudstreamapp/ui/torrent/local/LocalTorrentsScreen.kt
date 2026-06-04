package com.example.cloudstreamapp.ui.torrent.local

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.cloudstreamapp.data.torrent.local.LocalTorrentEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalTorrentsScreen(
    onBack: () -> Unit,
    onOpenTorrent: (infoHash: String) -> Unit,
    onOpenPlaylist: (String) -> Unit = {},
    viewModel: LocalTorrentsViewModel = hiltViewModel(),
) {
    val torrents by viewModel.torrents.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LocalTorrentsViewModel.Event.OpenPlaylist -> onOpenPlaylist(event.playlistId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мои торренты") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        }
    ) { padding ->
        if (torrents.isEmpty()) {
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
                    Text("Нет сохранённых торрентов", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Откройте .torrent файл с устройства — он появится здесь автоматически",
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
                items(torrents, key = { it.infoHash }) { entry ->
                    LocalTorrentItem(
                        entry = entry,
                        onClick = { onOpenTorrent(entry.infoHash) },
                        onCreatePlaylist = { viewModel.createPlaylist(entry) },
                        onDelete = { viewModel.delete(entry.infoHash) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LocalTorrentItem(
    entry: LocalTorrentEntity,
    onClick: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = {
            Text(
                text = entry.torrentName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = entry.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatDate(entry.addedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onCreatePlaylist, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.PlaylistAdd,
                        contentDescription = "Создать плейлист",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Удалить из списка",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale("ru"))
private fun formatDate(millis: Long): String = dateFormat.format(Date(millis))
