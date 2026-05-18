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
import com.example.cloudstreamapp.core.utils.toHumanReadableSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentDownloadsScreen(
    onBack: () -> Unit,
    onOpenGroup: (torrentName: String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    viewModel: TorrentDownloadsViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TorrentDownloadsViewModel.Event.OpenGroup -> onOpenGroup(event.torrentName)
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
                items(groups, key = { it.torrentName }) { group ->
                    TorrentGroupItem(
                        group = group,
                        onClick = { viewModel.openGroup(group) },
                        onCreatePlaylist = { viewModel.createPlaylist(group) },
                        onDelete = { viewModel.deleteGroup(group) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TorrentGroupItem(
    group: TorrentDownloadsViewModel.DownloadGroup,
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
                text = group.torrentName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "${pluralTracks(group.trackCount)} · ${group.totalSizeBytes.toHumanReadableSize()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDate(group.lastDownloadedAt),
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
                        contentDescription = "Удалить все треки",
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

private fun pluralTracks(count: Int): String = when {
    count % 100 in 11..19 -> "$count треков"
    count % 10 == 1 -> "$count трек"
    count % 10 in 2..4 -> "$count трека"
    else -> "$count треков"
}
