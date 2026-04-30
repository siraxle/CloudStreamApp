package com.example.cloudstreamapp.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.cloudstreamapp.core.utils.isAudioFile
import com.example.cloudstreamapp.core.utils.isVideoFile
import com.example.cloudstreamapp.domain.model.CloudItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onPlayMedia: (CloudItem) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text("Поиск по названию, исполнителю...") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = viewModel::clearQuery) {
                                    Icon(Icons.Default.Clear, contentDescription = "Очистить")
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                query.isBlank() -> {
                    Text(
                        text = "Введите запрос для поиска",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                results.isEmpty() -> {
                    Text(
                        text = "Ничего не найдено",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    LazyColumn {
                        items(results, key = { it.id }) { item ->
                            val icon = when {
                                item.name.isAudioFile() -> Icons.Default.AudioFile
                                item.name.isVideoFile() -> Icons.Default.VideoFile
                                else -> Icons.AutoMirrored.Filled.InsertDriveFile
                            }
                            val subtitle = buildString {
                                item.mimeType?.let { append(it) }
                                item.sizeBytes?.let { bytes ->
                                    if (isNotEmpty()) append(" · ")
                                    append("${bytes / 1024 / 1024} МБ")
                                }
                            }
                            ListItem(
                                headlineContent = {
                                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = if (subtitle.isNotEmpty()) {
                                    { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                } else null,
                                leadingContent = {
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp))
                                },
                                modifier = Modifier.clickable { onPlayMedia(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}
