package com.example.cloudstreamapp.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.cloudstreamapp.core.utils.isAudioFile
import com.example.cloudstreamapp.core.utils.isVideoFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onPlayTrack: (index: Int) -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val tracks by viewModel.tracks.collectAsState()
    val name by viewModel.playlistName.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = { Text(name) },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (tracks.isEmpty()) {
                Text(
                    text = "Плейлист пуст",
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn {
                    itemsIndexed(tracks, key = { _, row -> row.item.id }) { index, row ->
                        val cloudItem = row.cloudItem
                        val displayName = cloudItem?.name ?: "Неизвестный трек"
                        val icon = when {
                            cloudItem == null -> Icons.AutoMirrored.Filled.InsertDriveFile
                            cloudItem.name.isAudioFile() -> Icons.Default.AudioFile
                            cloudItem.name.isVideoFile() -> Icons.Default.VideoFile
                            else -> Icons.AutoMirrored.Filled.InsertDriveFile
                        }
                        ListItem(
                            headlineContent = {
                                Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp))
                            },
                            trailingContent = {
                                IconButton(onClick = { viewModel.removeTrack(row.item.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Удалить из плейлиста")
                                }
                            },
                            modifier = Modifier.clickable(enabled = cloudItem != null) {
                                onPlayTrack(index)
                            },
                        )
                    }
                }
            }
        }
    }
}
