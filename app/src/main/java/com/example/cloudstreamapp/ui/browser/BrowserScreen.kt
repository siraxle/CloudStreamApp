package com.example.cloudstreamapp.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
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
import com.example.cloudstreamapp.core.utils.isAudioFile
import com.example.cloudstreamapp.core.utils.isVideoFile
import com.example.cloudstreamapp.core.utils.toHumanReadableSize
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.Playlist

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onNavigateToFolder: (sourceId: String, path: String) -> Unit,
    onPlayMedia: (CloudItem) -> Unit,
    onBack: () -> Unit,
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val pathStack by viewModel.pathStack.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val playlistMessage by viewModel.playlistMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var addToPlaylistItem by remember { mutableStateOf<CloudItem?>(null) }

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
                else -> LazyColumn {
                    items(items, key = { it.id }) { item ->
                        CloudItemRow(
                            item = item,
                            onClick = {
                                if (item.type == CloudItem.ItemType.DIRECTORY) {
                                    viewModel.navigateTo(item.path.relativePath)
                                } else {
                                    onPlayMedia(item)
                                }
                            },
                            onLongClick = {
                                if (item.type == CloudItem.ItemType.FILE) {
                                    addToPlaylistItem = item
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    addToPlaylistItem?.let { item ->
        AddToPlaylistDialog(
            item = item,
            playlists = playlists,
            onDismiss = { addToPlaylistItem = null },
            onSelectPlaylist = { playlistId ->
                viewModel.addToPlaylist(item, playlistId)
                addToPlaylistItem = null
            },
            onCreateNew = { name ->
                viewModel.createPlaylistAndAdd(item, name)
                addToPlaylistItem = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CloudItemRow(
    item: CloudItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val icon = when {
        item.type == CloudItem.ItemType.DIRECTORY -> Icons.Default.Folder
        item.name.isAudioFile() -> Icons.Default.AudioFile
        item.name.isVideoFile() -> Icons.Default.VideoFile
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

    ListItem(
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { item.sizeBytes?.let { Text(it.toHumanReadableSize()) } },
        leadingContent = { Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp)) },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
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

@Composable
private fun AddToPlaylistDialog(
    item: CloudItem,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (String) -> Unit,
    onCreateNew: (String) -> Unit,
) {
    var showNewPlaylistField by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить в плейлист") },
        text = {
            Column {
                Text(
                    text = item.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                if (playlists.isNotEmpty()) {
                    playlists.forEach { playlist ->
                        TextButton(
                            onClick = { onSelectPlaylist(playlist.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(playlist.name)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }

                if (showNewPlaylistField) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Название плейлиста") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TextButton(
                        onClick = { showNewPlaylistField = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("+ Новый плейлист")
                    }
                }
            }
        },
        confirmButton = {
            if (showNewPlaylistField) {
                TextButton(
                    onClick = { if (newName.isNotBlank()) onCreateNew(newName.trim()) },
                ) { Text("Создать") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
