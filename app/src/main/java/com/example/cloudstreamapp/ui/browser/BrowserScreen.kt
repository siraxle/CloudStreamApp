package com.example.cloudstreamapp.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.material3.MaterialTheme
import com.example.cloudstreamapp.core.utils.isAudioFile
import com.example.cloudstreamapp.core.utils.isVideoFile
import com.example.cloudstreamapp.core.utils.toHumanReadableSize
import com.example.cloudstreamapp.domain.model.CacheStatus
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.Playlist

// What the user wants to add to a playlist
private sealed class PlaylistTarget {
    data class File(val item: CloudItem) : PlaylistTarget()
    data class Folder(val item: CloudItem) : PlaylistTarget()

    val displayName: String get() = when (this) {
        is File -> item.name
        is Folder -> "Папка «${item.name}»"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onNavigateToFolder: (sourceId: String, path: String) -> Unit,
    onPlayMedia: (item: CloudItem, folderPath: String) -> Unit,
    onBack: () -> Unit,
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val pathStack by viewModel.pathStack.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val playlistMessage by viewModel.playlistMessage.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var playlistTarget by remember { mutableStateOf<PlaylistTarget?>(null) }
    var isGridView by rememberSaveable { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    BackHandler { if (!viewModel.navigateUp()) onBack() }

    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar("Ошибка: $error")
            viewModel.dismissError()
        }
    }
    LaunchedEffect(playlistMessage) {
        if (playlistMessage != null) {
            snackbarHostState.showSnackbar(playlistMessage!!)
            viewModel.dismissPlaylistMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.navigateUp()) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = {
                    Breadcrumbs(
                        pathStack = pathStack,
                        onCrumbClick = { viewModel.navigateToIndex(it) },
                    )
                },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Сортировка")
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                        ) {
                            SortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = { Text(order.label) },
                                    trailingIcon = {
                                        if (order == sortOrder) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        viewModel.setSortOrder(order)
                                        sortMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { isGridView = !isGridView }) {
                        Icon(
                            if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = if (isGridView) "Список" else "Сетка",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                items.isEmpty() -> Text("Папка пуста", modifier = Modifier.align(Alignment.Center))
                isGridView -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(8.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        CloudItemCard(
                            item = item,
                            onClick = {
                                if (item.type == CloudItem.ItemType.DIRECTORY) {
                                    viewModel.navigateTo(item.path.relativePath)
                                } else {
                                    onPlayMedia(item, viewModel.currentPath)
                                }
                            },
                            onAddToPlaylist = {
                                playlistTarget = if (item.type == CloudItem.ItemType.DIRECTORY) {
                                    PlaylistTarget.Folder(item)
                                } else {
                                    PlaylistTarget.File(item)
                                }
                            },
                        )
                    }
                }
                else -> LazyColumn {
                    items(items, key = { it.id }) { item ->
                        CloudItemRow(
                            item = item,
                            onClick = {
                                if (item.type == CloudItem.ItemType.DIRECTORY) {
                                    viewModel.navigateTo(item.path.relativePath)
                                } else {
                                    onPlayMedia(item, viewModel.currentPath)
                                }
                            },
                            onPlay = { onPlayMedia(item, viewModel.currentPath) },
                            onAddToPlaylist = {
                                playlistTarget = if (item.type == CloudItem.ItemType.DIRECTORY) {
                                    PlaylistTarget.Folder(item)
                                } else {
                                    PlaylistTarget.File(item)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    playlistTarget?.let { target ->
        AddToPlaylistDialog(
            targetName = target.displayName,
            suggestedName = if (target is PlaylistTarget.Folder) target.item.name else null,
            playlists = playlists,
            onDismiss = { playlistTarget = null },
            onSelectPlaylist = { playlistId ->
                when (target) {
                    is PlaylistTarget.File -> viewModel.addToPlaylist(target.item, playlistId)
                    is PlaylistTarget.Folder -> viewModel.addFolderToPlaylist(target.item, playlistId)
                }
                playlistTarget = null
            },
            onCreateNew = { name ->
                when (target) {
                    is PlaylistTarget.File -> viewModel.createPlaylistAndAdd(target.item, name)
                    is PlaylistTarget.Folder -> viewModel.createPlaylistAndAddFolder(target.item, name)
                }
                playlistTarget = null
            },
        )
    }
}

@Composable
private fun CloudItemCard(
    item: CloudItem,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    val isFile = item.type == CloudItem.ItemType.FILE
    val icon = when {
        !isFile -> Icons.Default.Folder
        item.name.isAudioFile() -> Icons.Default.AudioFile
        item.name.isVideoFile() -> Icons.Default.VideoFile
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    var menuExpanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.clickable(onClick = onClick)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(8.dp),
        ) {
            Box {
                Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp).align(Alignment.Center))
                // Cache status badge
                if (isFile && item.cacheStatus != CacheStatus.REMOTE) {
                    Icon(
                        imageVector = if (item.cacheStatus == CacheStatus.CACHED) Icons.Default.OfflinePin else Icons.Default.Downloading,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).align(Alignment.BottomStart),
                        tint = if (item.cacheStatus == CacheStatus.CACHED)
                            MaterialTheme.colorScheme.tertiary
                        else
                            MaterialTheme.colorScheme.primary,
                    )
                }
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Действия", modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(if (isFile) "Добавить в плейлист" else "Добавить папку в плейлист") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                            onClick = { menuExpanded = false; onAddToPlaylist() },
                        )
                    }
                }
            }
            Text(
                text = item.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CloudItemRow(
    item: CloudItem,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    val isFile = item.type == CloudItem.ItemType.FILE
    val icon = when {
        !isFile -> Icons.Default.Folder
        item.name.isAudioFile() -> Icons.Default.AudioFile
        item.name.isVideoFile() -> Icons.Default.VideoFile
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

    var menuExpanded by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { item.sizeBytes?.let { Text(it.toHumanReadableSize()) } },
        leadingContent = { Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Cache status icon
                if (isFile && item.cacheStatus != CacheStatus.REMOTE) {
                    Icon(
                        imageVector = if (item.cacheStatus == CacheStatus.CACHED) Icons.Default.OfflinePin else Icons.Default.Downloading,
                        contentDescription = if (item.cacheStatus == CacheStatus.CACHED) "Доступно офлайн" else "Загружается",
                        modifier = Modifier.size(18.dp),
                        tint = if (item.cacheStatus == CacheStatus.CACHED)
                            MaterialTheme.colorScheme.tertiary
                        else
                            MaterialTheme.colorScheme.primary,
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Действия")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        if (isFile) {
                            DropdownMenuItem(
                                text = { Text("Воспроизвести") },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                                onClick = { menuExpanded = false; onPlay() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (isFile) "Добавить в плейлист" else "Добавить папку в плейлист") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                            onClick = { menuExpanded = false; onAddToPlaylist() },
                        )
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Breadcrumbs(
    pathStack: List<String>,
    onCrumbClick: (Int) -> Unit,
) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        pathStack.forEachIndexed { index, segment ->
            if (index > 0) {
                Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            TextButton(onClick = { onCrumbClick(index) }) {
                Text(
                    text = if (segment == "root" || segment == "/") "Root"
                           else segment.substringAfterLast('/').ifEmpty { segment },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val PLAYLIST_PAGE_SIZE = 5

@Composable
private fun AddToPlaylistDialog(
    targetName: String,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (String) -> Unit,
    onCreateNew: (String) -> Unit,
    suggestedName: String? = null,
) {
    var showNewField by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf(suggestedName.orEmpty()) }
    var currentPage by rememberSaveable { mutableStateOf(0) }
    val nameExists = playlists.any { it.name == newName.trim() }
    val totalPages = if (playlists.isEmpty()) 0
                     else (playlists.size + PLAYLIST_PAGE_SIZE - 1) / PLAYLIST_PAGE_SIZE
    val pagePlaylists = playlists.drop(currentPage * PLAYLIST_PAGE_SIZE).take(PLAYLIST_PAGE_SIZE)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить в плейлист") },
        text = {
            Column {
                Text(
                    text = targetName,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (playlists.isNotEmpty()) {
                    pagePlaylists.forEach { playlist ->
                        TextButton(
                            onClick = { onSelectPlaylist(playlist.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    if (totalPages > 1) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            IconButton(
                                onClick = { currentPage-- },
                                enabled = currentPage > 0,
                            ) { Icon(Icons.Default.ChevronLeft, contentDescription = "Назад") }
                            Text(
                                text = "${currentPage + 1} / $totalPages",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            IconButton(
                                onClick = { currentPage++ },
                                enabled = currentPage < totalPages - 1,
                            ) { Icon(Icons.Default.ChevronRight, contentDescription = "Вперёд") }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                if (showNewField) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Название плейлиста") },
                        singleLine = true,
                        isError = nameExists,
                        supportingText = if (nameExists) {
                            { Text("Плейлист с таким именем уже существует") }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TextButton(
                        onClick = { showNewField = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("+ Новый плейлист") }
                }
            }
        },
        confirmButton = {
            if (showNewField) {
                TextButton(
                    enabled = newName.isNotBlank() && !nameExists,
                    onClick = { onCreateNew(newName.trim()) },
                ) { Text("Создать") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
