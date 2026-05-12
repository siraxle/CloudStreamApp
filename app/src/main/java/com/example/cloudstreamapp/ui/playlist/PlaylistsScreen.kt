package com.example.cloudstreamapp.ui.playlist

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    onPlaylistClick: (String) -> Unit = {},
    onFavoritesClick: () -> Unit = {},
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsState()
    val pendingDeleteId by viewModel.pendingDeleteId.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    val openFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFromUri(it) } }

    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.bulkExportToUri(it) } }

    LaunchedEffect(viewModel) {
        viewModel.importResult.collect { result ->
            when (result) {
                is PlaylistsViewModel.ImportResult.Single ->
                    onPlaylistClick(result.playlistId)
                is PlaylistsViewModel.ImportResult.Multiple ->
                    snackbarHostState.showSnackbar(
                        "Импортировано ${pluralPlaylists(result.count)}"
                    )
                PlaylistsViewModel.ImportResult.Error ->
                    snackbarHostState.showSnackbar(
                        "Не удалось импортировать плейлист. Проверьте файл."
                    )
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.bulkExportFileSuggestion.collect { filename ->
            saveFileLauncher.launch(filename)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.bulkExportResult.collect { result ->
            when (result) {
                is PlaylistsViewModel.ExportResult.Success ->
                    snackbarHostState.showSnackbar(
                        "Экспортировано ${pluralPlaylists(result.count)}"
                    )
                PlaylistsViewModel.ExportResult.Error ->
                    snackbarHostState.showSnackbar("Ошибка при экспорте")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Отмена")
                        }
                    },
                    title = { Text("Выбрано ${selectedIds.size}") },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Выбрать все")
                        }
                        IconButton(
                            onClick = { viewModel.requestBulkExport() },
                            enabled = selectedIds.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Экспортировать выбранные")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Плейлисты") },
                    actions = {
                        IconButton(onClick = { showImportDialog = true }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Импортировать")
                        }
                        IconButton(onClick = onFavoritesClick) {
                            Icon(Icons.Default.Bookmarks, contentDescription = "Избранные плейлисты")
                        }
                        IconButton(
                            onClick = { viewModel.enterSelectionMode() },
                            enabled = playlists.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.PlaylistAddCheck, contentDescription = "Выбрать для экспорта")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Новый плейлист")
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (playlists.isEmpty()) {
                Text(
                    text = "Нет плейлистов. Нажмите + для создания.",
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn {
                    items(playlists, key = { it.playlist.id }) { item ->
                        val isSelected = item.playlist.id in selectedIds
                        PlaylistRow(
                            item = item,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onClick = {
                                if (isSelectionMode) viewModel.toggleSelection(item.playlist.id)
                                else onPlaylistClick(item.playlist.id)
                            },
                            onDelete = { viewModel.requestDeletePlaylist(item.playlist.id) },
                            onToggleFavorite = { viewModel.toggleFavorite(item.playlist.id) },
                        )
                    }
                }
            }
        }
    }

    if (pendingDeleteId != null) {
        val item = playlists.firstOrNull { it.playlist.id == pendingDeleteId }
        val name = item?.playlist?.name ?: ""
        AlertDialog(
            onDismissRequest = { viewModel.cancelDeletePlaylist() },
            title = { Text("Удалить плейлист?") },
            text = {
                val base = "«$name» будет удалён вместе со всеми треками. " +
                    "Скачанные треки, не используемые в других плейлистах, " +
                    "также будут удалены с устройства."
                val favoriteHint = if (item?.isFavorite == true)
                    "\n\nПлейлист добавлен в избранное — вы сможете восстановить его оттуда."
                else ""
                Text(base + favoriteHint)
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDeletePlaylist() }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDeletePlaylist() }) { Text("Отмена") }
            },
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Импорт плейлиста") },
            text = { Text("Выберите JSON-файл с сохранённым плейлистом или архивом плейлистов.") },
            confirmButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    openFileLauncher.launch(arrayOf("application/json", "*/*"))
                }) {
                    Text("Выбрать файл")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Отмена") }
            },
        )
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                viewModel.createPlaylist(name)
                showCreateDialog = false
            },
        )
    }
}

@Composable
private fun PlaylistRow(
    item: PlaylistsViewModel.PlaylistUiItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(item.playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = pluralTracks(item.totalTracks),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.cachedTracks > 0) {
                    Icon(
                        Icons.Default.OfflinePin,
                        contentDescription = "На устройстве",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        text = "${item.cachedTracks}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (item.downloadingTracks > 0) {
                    Icon(
                        Icons.Default.Downloading,
                        contentDescription = "Загружается",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "${item.downloadingTracks}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        leadingContent = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
        trailingContent = {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                )
            } else {
                Row {
                    IconButton(onClick = onToggleFavorite) {
                        if (item.isFavorite) {
                            Icon(
                                Icons.Default.Bookmark,
                                contentDescription = "Убрать из избранного",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(
                                Icons.Default.BookmarkBorder,
                                contentDescription = "Добавить в избранное",
                            )
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить")
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun pluralTracks(count: Int): String = when {
    count % 100 in 11..19 -> "$count треков"
    count % 10 == 1 -> "$count трек"
    count % 10 in 2..4 -> "$count трека"
    else -> "$count треков"
}

private fun pluralPlaylists(count: Int): String = when {
    count % 100 in 11..19 -> "$count плейлистов"
    count % 10 == 1 -> "$count плейлист"
    count % 10 in 2..4 -> "$count плейлиста"
    else -> "$count плейлистов"
}

@Composable
private fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый плейлист") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }) {
                Text("Создать")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
