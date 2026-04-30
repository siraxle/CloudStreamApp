package com.example.cloudstreamapp.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.cloudstreamapp.core.utils.isAudioFile
import com.example.cloudstreamapp.core.utils.isVideoFile
import com.example.cloudstreamapp.core.utils.toHumanReadableSize
import com.example.cloudstreamapp.domain.model.CloudItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onNavigateToFolder: (sourceId: String, path: String) -> Unit,
    onPlayMedia: (mediaId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val pathStack by viewModel.pathStack.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler {
        if (!viewModel.navigateUp()) onBack()
    }

    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar("Ошибка: $error")
            viewModel.dismissError()
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
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                items.isEmpty() -> {
                    Text(
                        text = "Папка пуста",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    LazyColumn {
                        items(items, key = { it.id }) { item ->
                            CloudItemRow(
                                item = item,
                                onClick = {
                                    if (item.type == CloudItem.ItemType.DIRECTORY) {
                                        viewModel.navigateTo(item.path.relativePath)
                                    } else {
                                        onPlayMedia(item.id)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(
    pathStack: List<String>,
    onCrumbClick: (Int) -> Unit,
) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        pathStack.forEachIndexed { index, segment ->
            if (index > 0) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            TextButton(onClick = { onCrumbClick(index) }) {
                Text(
                    text = when {
                        segment == "root" || segment == "/" -> "Root"
                        else -> segment.substringAfterLast('/').ifEmpty { segment }
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CloudItemRow(
    item: CloudItem,
    onClick: () -> Unit,
) {
    val icon = when {
        item.type == CloudItem.ItemType.DIRECTORY -> Icons.Default.Folder
        item.name.isAudioFile() -> Icons.Default.AudioFile
        item.name.isVideoFile() -> Icons.Default.VideoFile
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

    ListItem(
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            item.sizeBytes?.let { Text(it.toHumanReadableSize()) }
        },
        leadingContent = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp))
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
