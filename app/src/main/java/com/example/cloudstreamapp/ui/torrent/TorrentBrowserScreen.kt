package com.example.cloudstreamapp.ui.torrent

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.cloudstreamapp.core.utils.toHumanReadableSize
import com.example.cloudstreamapp.data.torrent.provider.ContentCategory
import com.example.cloudstreamapp.data.torrent.provider.TorrentSource
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.torrent.DownloadProgress
import com.example.cloudstreamapp.domain.torrent.TorrentResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentBrowserScreen(
    onPlayFile: (item: CloudItem, magnetUri: String, infoHash: String) -> Unit,
    onOpenDownloads: () -> Unit = {},
    onOpenLocalTorrents: () -> Unit = {},
    onOpenPlaylist: (String) -> Unit = {},
    localTorrentToOpen: String = "",
    onLocalTorrentConsumed: () -> Unit = {},
    viewModel: TorrentBrowserViewModel = hiltViewModel(),
) {
    // Open a torrent forwarded from LocalTorrentsScreen
    LaunchedEffect(localTorrentToOpen) {
        if (localTorrentToOpen.isNotEmpty()) {
            viewModel.openLocalTorrent(localTorrentToOpen)
            onLocalTorrentConsumed()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TorrentBrowserViewModel.Event.OpenPlaylist -> onOpenPlaylist(event.playlistId)
            }
        }
    }

    val uiState by viewModel.uiState.collectAsState()
    val query by viewModel.query.collectAsState()
    val category by viewModel.category.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val context = LocalContext.current

    val torrentFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) { null }
        if (bytes == null) return@rememberLauncherForActivityResult
        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "file.torrent"
        viewModel.openTorrentFile(bytes, fileName)
    }

    // True when viewing a .torrent file opened from device storage at the torrent root.
    // In this case there are no search results to return to, so back navigation must be
    // absorbed (doing nothing) to prevent clearing the file list state.
    val isDeviceTorrentAtRoot = (uiState as? TorrentBrowserViewModel.UiState.FileList)?.let {
        it.currentPath.isEmpty() && it.magnetUri.startsWith("torrent:")
    } ?: false

    // Inside a subfolder → navigate up.
    // At root of a search-result torrent → back to results.
    // At root of a device-opened torrent → absorb without action (torrent stays in the list).
    BackHandler(enabled = uiState is TorrentBrowserViewModel.UiState.FileList) {
        viewModel.navigateUp()
    }

    val downloadCount = downloadProgress.values.count { it is DownloadProgress.Done }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Торренты") },
                navigationIcon = {
                    if (uiState is TorrentBrowserViewModel.UiState.FileList) {
                        if (isDeviceTorrentAtRoot) {
                            // Close button: explicitly exits the torrent view and returns to search.
                            // System back is intentionally absorbed without action so the torrent
                            // is not lost on accidental press; this button is the deliberate exit.
                            IconButton(onClick = { viewModel.backToResults() }) {
                                Icon(Icons.Default.Close, contentDescription = "Закрыть торрент")
                            }
                        } else {
                            IconButton(onClick = { viewModel.navigateUp() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenLocalTorrents) {
                        Icon(
                            Icons.Default.Storage,
                            contentDescription = "Мои торренты",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onOpenDownloads) {
                        Icon(
                            Icons.Default.OfflinePin,
                            contentDescription = "Скачанные треки",
                            tint = if (downloadCount > 0) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
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
            val showSearchBar = uiState !is TorrentBrowserViewModel.UiState.ResolvingMagnet &&
                uiState !is TorrentBrowserViewModel.UiState.FileList

            if (showSearchBar) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::onQueryChanged,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Поиск или magnet-ссылка…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = { viewModel.search() },
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { viewModel.search() }) {
                        Icon(Icons.Default.Search, contentDescription = "Найти")
                    }
                    IconButton(onClick = { torrentFileLauncher.launch("*/*") }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Открыть .torrent файл")
                    }
                }
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = category == ContentCategory.AUDIO,
                        onClick = { viewModel.setCategory(ContentCategory.AUDIO) },
                        label = { Text("Музыка") },
                        leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                    FilterChip(
                        selected = category == ContentCategory.ALL,
                        onClick = { viewModel.setCategory(ContentCategory.ALL) },
                        label = { Text("Всё") },
                    )
                }
            }

            when (val state = uiState) {
                TorrentBrowserViewModel.UiState.Idle -> IdleHint()

                is TorrentBrowserViewModel.UiState.Searching -> CenteredProgress("Поиск: «${state.query}»")

                is TorrentBrowserViewModel.UiState.SearchResults -> SearchResultsContent(
                    state = state,
                    onFilterSource = viewModel::filterBySource,
                    onOpenResult = viewModel::openTorrentResult,
                )

                is TorrentBrowserViewModel.UiState.ResolvingMagnet ->
                    CenteredProgress("Получение метаданных…\n${state.name}")

                is TorrentBrowserViewModel.UiState.FileList -> {
                    val activeFolderPaths by viewModel.activeFolderDownloadPaths.collectAsState()
                    FileListContent(
                        state = state,
                        downloadProgressMap = downloadProgress,
                        activeFolderDownloadPaths = activeFolderPaths,
                        onPlayFile = onPlayFile,
                        onNavigateToFolder = viewModel::navigateToFolder,
                        onNavigateToBreadcrumb = viewModel::navigateToBreadcrumb,
                        onLoadPage = viewModel::loadPage,
                        onDownloadFile = viewModel::downloadFile,
                        onDownloadFolder = viewModel::downloadFolderItem,
                        onCancelDownload = viewModel::cancelDownload,
                        onCancelFolderDownload = viewModel::cancelFolderDownload,
                        onDeleteDownload = viewModel::deleteDownload,
                        onAddFolderToPlaylist = viewModel::addFolderToPlaylist,
                    )
                }

                is TorrentBrowserViewModel.UiState.Error -> ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.search() },
                )
            }
        }
    }
}

@Composable
private fun CenteredProgress(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun IdleHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text("Введите название или magnet-ссылку", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Или откройте .torrent файл с устройства кнопкой справа от строки поиска",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Источники включаются в Настройках → Торрент-источники",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaterialTheme.colorScheme.error, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Повторить") }
        }
    }
}

@Composable
private fun SearchResultsContent(
    state: TorrentBrowserViewModel.UiState.SearchResults,
    onFilterSource: (TorrentSource?) -> Unit,
    onOpenResult: (TorrentResult) -> Unit,
) {
    Column {
        if (state.availableSources.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = state.activeFilter == null,
                        onClick = { onFilterSource(null) },
                        label = { Text("Все (${state.totalCount})") },
                    )
                }
                items(state.availableSources) { src ->
                    FilterChip(
                        selected = state.activeFilter == src,
                        onClick = { onFilterSource(src) },
                        label = { Text(src.displayName) },
                    )
                }
            }
        }

        if (state.results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Ничего не найдено")
            }
        } else {
            LazyColumn {
                items(state.results, key = { it.infoHash.ifEmpty { it.magnetUri } }) { result ->
                    TorrentResultItem(result = result, onClick = { onOpenResult(result) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TorrentResultItem(result: TorrentResult, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(result.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result.sizeBytes > 0) {
                    Text(result.sizeBytes.toHumanReadableSize(),
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("↑${result.seeders}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
                Text("↓${result.leechers}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        },
        trailingContent = {
            SuggestionChip(onClick = {}, label = { Text(result.source) })
        },
    )
}

@Composable
private fun FileListContent(
    state: TorrentBrowserViewModel.UiState.FileList,
    downloadProgressMap: Map<String, DownloadProgress>,
    activeFolderDownloadPaths: Set<String>,
    onPlayFile: (item: CloudItem, magnetUri: String, infoHash: String) -> Unit,
    onNavigateToFolder: (CloudItem) -> Unit,
    onNavigateToBreadcrumb: (Int) -> Unit,
    onLoadPage: (Int) -> Unit,
    onDownloadFile: (CloudItem) -> Unit,
    onDownloadFolder: (CloudItem) -> Unit,
    onCancelDownload: (CloudItem) -> Unit,
    onCancelFolderDownload: (CloudItem) -> Unit,
    onDeleteDownload: (CloudItem) -> Unit,
    onAddFolderToPlaylist: (CloudItem) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BreadcrumbRow(state, onNavigateToBreadcrumb)

        when {
            state.totalCount == 0 -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Нет аудиофайлов в этой папке")
                }
            }
            else -> {
                LazyColumn(Modifier.weight(1f)) {
                    items(state.pageItems, key = { it.id }) { item ->
                        if (item.type == CloudItem.ItemType.DIRECTORY) {
                            FolderItem(
                                item = item,
                                isDownloading = item.path.relativePath in activeFolderDownloadPaths,
                                onClick = { onNavigateToFolder(item) },
                                onDownload = { onDownloadFolder(item) },
                                onCancelDownload = { onCancelFolderDownload(item) },
                                onAddToPlaylist = { onAddFolderToPlaylist(item) },
                            )
                        } else {
                            FileItem(
                                item = item,
                                dlProgress = downloadProgressMap[item.id],
                                onClick = { onPlayFile(item, state.magnetUri, state.infoHash) },
                                onDownload = { onDownloadFile(item) },
                                onCancelDownload = { onCancelDownload(item) },
                                onDeleteDownload = { onDeleteDownload(item) },
                            )
                        }
                        HorizontalDivider()
                    }
                }
                if (state.totalPages > 1) {
                    PaginationRow(state, onLoadPage)
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbRow(
    state: TorrentBrowserViewModel.UiState.FileList,
    onNavigateToBreadcrumb: (Int) -> Unit,
) {
    val parts = if (state.currentPath.isEmpty()) emptyList()
                else state.currentPath.split("/")

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            val isRoot = state.currentPath.isEmpty()
            Text(
                text = state.torrentName.ifBlank { "Торрент" },
                modifier = Modifier
                    .clickable(enabled = !isRoot) { onNavigateToBreadcrumb(0) }
                    .padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (isRoot) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        itemsIndexed(parts) { idx, part ->
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val isLast = idx == parts.lastIndex
            Text(
                text = part,
                modifier = Modifier
                    .clickable(enabled = !isLast) { onNavigateToBreadcrumb(idx + 1) }
                    .padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (isLast) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun FolderItem(
    item: CloudItem,
    isDownloading: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                IconButton(onClick = onAddToPlaylist, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.PlaylistAdd,
                        contentDescription = "Добавить папку в плейлист",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (isDownloading) {
                    IconButton(onClick = onCancelDownload, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Отменить скачивание папки",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Скачать папку",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun FileItem(
    item: CloudItem,
    dlProgress: DownloadProgress?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                item.sizeBytes?.let { size ->
                    Text(size.toHumanReadableSize(), style = MaterialTheme.typography.bodySmall)
                }
                if (dlProgress is DownloadProgress.Downloading) {
                    Spacer(Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { dlProgress.fraction },
                        modifier = Modifier.fillMaxWidth(0.7f),
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            when (dlProgress) {
                null, is DownloadProgress.Failed -> {
                    IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Скачать",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is DownloadProgress.Queued -> {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                is DownloadProgress.Downloading -> {
                    IconButton(onClick = onCancelDownload, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Отменить",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is DownloadProgress.Done -> {
                    IconButton(onClick = onDeleteDownload, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Скачано — нажмите для удаления",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun PaginationRow(
    state: TorrentBrowserViewModel.UiState.FileList,
    onLoadPage: (Int) -> Unit,
) {
    HorizontalDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onLoadPage(state.page - 1) },
            enabled = state.page > 1,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Предыдущая страница")
        }

        Text(
            text = "${state.page} / ${state.totalPages}  (${state.totalCount})",
            style = MaterialTheme.typography.bodySmall,
        )

        IconButton(
            onClick = { onLoadPage(state.page + 1) },
            enabled = state.page < state.totalPages,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Следующая страница")
        }
    }
}
