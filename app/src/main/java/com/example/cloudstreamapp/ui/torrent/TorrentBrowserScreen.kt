package com.example.cloudstreamapp.ui.torrent

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.cloudstreamapp.core.utils.toHumanReadableSize
import com.example.cloudstreamapp.data.torrent.provider.ContentCategory
import com.example.cloudstreamapp.data.torrent.provider.TorrentSource
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.torrent.TorrentResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentBrowserScreen(
    onPlayFile: (item: CloudItem, magnetUri: String, infoHash: String) -> Unit,
    viewModel: TorrentBrowserViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val query by viewModel.query.collectAsState()
    val category by viewModel.category.collectAsState()

    BackHandler(enabled = uiState is TorrentBrowserViewModel.UiState.FileList) {
        viewModel.backToResults()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Торренты") },
                navigationIcon = {
                    if (uiState is TorrentBrowserViewModel.UiState.FileList) {
                        IconButton(onClick = { viewModel.backToResults() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                        }
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
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { viewModel.search() }) {
                        Icon(Icons.Default.Search, contentDescription = "Найти")
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

                is TorrentBrowserViewModel.UiState.FileList -> FileListContent(
                    state = state,
                    onPlayFile = onPlayFile,
                )

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
    val presentSources = remember(state.results) {
        state.results.map { it.source }.distinct()
    }
    val allSources = TorrentSource.entries.filter { it.displayName in presentSources }

    Column {
        if (allSources.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = state.activeFilter == null,
                        onClick = { onFilterSource(null) },
                        label = { Text("Все (${state.results.size})") },
                    )
                }
                items(allSources) { src ->
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
    onPlayFile: (item: CloudItem, magnetUri: String, infoHash: String) -> Unit,
) {
    if (state.items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Нет аудиофайлов в этом торренте")
        }
        return
    }
    LazyColumn {
        items(state.items, key = { it.id }) { item ->
            ListItem(
                modifier = Modifier.clickable {
                    onPlayFile(item, state.magnetUri, state.infoHash)
                },
                headlineContent = {
                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = item.sizeBytes?.let { size ->
                    { Text(size.toHumanReadableSize(), style = MaterialTheme.typography.bodySmall) }
                },
                leadingContent = {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            HorizontalDivider()
        }
    }
}
