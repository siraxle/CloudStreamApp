package com.example.cloudstreamapp.ui.torrent.downloads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.cloudstreamapp.data.torrent.download.TorrentDownloadEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentGroupDetailScreen(
    onBack: () -> Unit,
    onPlayTrack: (TorrentDownloadEntity) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    viewModel: TorrentGroupDetailViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val canNavigateUp by viewModel.canNavigateUp.collectAsState()
    val breadcrumbs by viewModel.breadcrumbs.collectAsState()
    val pendingDelete by viewModel.pendingDelete.collectAsState()
    val pendingDeleteFolder by viewModel.pendingDeleteFolder.collectAsState()

    BackHandler(enabled = canNavigateUp) {
        viewModel.navigateUp()
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TorrentGroupDetailViewModel.Event.PlayTrack -> onPlayTrack(event.entity)
                is TorrentGroupDetailViewModel.Event.OpenPlaylist -> onOpenPlaylist(event.playlistId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (canNavigateUp)
                            breadcrumbs.lastOrNull()?.name ?: viewModel.torrentName
                        else
                            viewModel.torrentName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (canNavigateUp) viewModel.navigateUp()
                        else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.createPlaylist() }) {
                        Icon(
                            Icons.Default.PlaylistAdd,
                            contentDescription = "Создать плейлист",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (canNavigateUp) {
                BreadcrumbBar(
                    breadcrumbs = breadcrumbs,
                    onNavigate = { viewModel.navigateTo(it) },
                )
                HorizontalDivider()
            }

            when {
                items.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Нет треков", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items,
                            key = { item ->
                                when (item) {
                                    is TorrentGroupDetailViewModel.BrowseItem.Folder -> "folder:${item.fullPath}"
                                    is TorrentGroupDetailViewModel.BrowseItem.Track -> "track:${item.entity.id}"
                                }
                            },
                        ) { item ->
                            when (item) {
                                is TorrentGroupDetailViewModel.BrowseItem.Folder -> {
                                    FolderRow(
                                        folder = item,
                                        onClick = { viewModel.navigateInto(item) },
                                        onDelete = { viewModel.requestDeleteFolder(item) },
                                    )
                                    HorizontalDivider()
                                }
                                is TorrentGroupDetailViewModel.BrowseItem.Track -> {
                                    TrackRow(
                                        entity = item.entity,
                                        onPlay = { viewModel.playTrack(item.entity) },
                                        onDelete = { viewModel.requestDelete(item.entity) },
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                                }
                            }
                        }
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

    if (pendingDeleteFolder != null) {
        val folder = pendingDeleteFolder!!
        AlertDialog(
            onDismissRequest = { viewModel.cancelDeleteFolder() },
            title = { Text("Удалить папку?") },
            text = {
                Text(
                    "Папка «${folder.name}» и все ${pluralTracks(folder.trackCount)} внутри " +
                    "будут удалены с устройства."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDeleteFolder() }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDeleteFolder() }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun BreadcrumbBar(
    breadcrumbs: List<TorrentGroupDetailViewModel.Breadcrumb>,
    onNavigate: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        breadcrumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val isLast = index == breadcrumbs.lastIndex
            Text(
                text = crumb.name,
                style = MaterialTheme.typography.bodySmall,
                color = if (isLast) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.primary,
                modifier = if (!isLast) Modifier.clickable { onNavigate(crumb.path) } else Modifier,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FolderRow(
    folder: TorrentGroupDetailViewModel.BrowseItem.Folder,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = {
            Text(
                text = folder.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = "${pluralTracks(folder.trackCount)} · ${folder.totalSizeBytes.toHumanReadableSize()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Удалить папку",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun TrackRow(
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
