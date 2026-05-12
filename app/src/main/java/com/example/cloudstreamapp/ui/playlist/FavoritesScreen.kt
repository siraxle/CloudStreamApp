package com.example.cloudstreamapp.ui.playlist

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onNavigateToPlaylist: (String) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val favorites by viewModel.favorites.collectAsState()
    val pendingDeleteId by viewModel.pendingDeleteId.collectAsState()
    val restoredPlaylistId by viewModel.restoredPlaylistId.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportToUri(it) } }

    LaunchedEffect(restoredPlaylistId) {
        restoredPlaylistId?.let {
            viewModel.consumeRestoredPlaylistId()
            onNavigateToPlaylist(it)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.exportFileSuggestion.collect { filename ->
            saveFileLauncher.launch(filename)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.exportResult.collect { result ->
            val message = when (result) {
                FavoritesViewModel.ExportResult.Success -> "Плейлист экспортирован"
                FavoritesViewModel.ExportResult.Error -> "Ошибка при экспорте"
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Избранные плейлисты") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
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
            if (favorites.isEmpty()) {
                Text(
                    text = "Нет избранных плейлистов.\nДобавьте плейлист в избранное значком закладки.",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(padding),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(favorites, key = { it.favorite.id }) { item ->
                        FavoriteRow(
                            item = item,
                            onRestore = { viewModel.restore(item.favorite.id) },
                            onNavigate = {
                                item.favorite.originalPlaylistId?.let { onNavigateToPlaylist(it) }
                            },
                            onExport = { viewModel.requestExport(item.favorite.id) },
                            onDelete = { viewModel.requestDelete(item.favorite.id) },
                        )
                    }
                }
            }
        }
    }

    if (pendingDeleteId != null) {
        val name = favorites.firstOrNull { it.favorite.id == pendingDeleteId }?.favorite?.name ?: ""
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text("Удалить из избранного?") },
            text = { Text("«$name» будет удалён из избранного. Сам плейлист и его треки не затрагиваются.") },
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
private fun FavoriteRow(
    item: FavoritesViewModel.FavoriteUiItem,
    onRestore: () -> Unit,
    onNavigate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(item.favorite.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val trackCount = item.favorite.tracks.size
            val status = if (item.isInMainList) "В списке плейлистов" else "Удалён из плейлистов"
            Text(
                text = "${pluralFavTracks(trackCount)} · $status",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(
                if (item.isInMainList) Icons.Default.QueueMusic else Icons.Default.CloudOff,
                contentDescription = null,
                tint = if (item.isInMainList)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.outline,
            )
        },
        trailingContent = {
            Row {
                if (item.isInMainList) {
                    TextButton(onClick = onNavigate) { Text("Открыть") }
                } else {
                    IconButton(onClick = onRestore) {
                        Icon(
                            Icons.Default.Restore,
                            contentDescription = "Восстановить плейлист",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = onExport) {
                    Icon(Icons.Default.Share, contentDescription = "Экспортировать")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить из избранного")
                }
            }
        },
    )
}

private fun pluralFavTracks(count: Int): String = when {
    count % 100 in 11..19 -> "$count треков"
    count % 10 == 1 -> "$count трек"
    count % 10 in 2..4 -> "$count трека"
    else -> "$count треков"
}
