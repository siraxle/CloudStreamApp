package com.example.cloudstreamapp.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.example.cloudstreamapp.domain.model.CloudItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGalleryScreen(
    onBack: () -> Unit,
    viewModel: ImageGalleryViewModel = hiltViewModel(),
) {
    val images by viewModel.images.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val pagerState = rememberPagerState(pageCount = { images.size })

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = {
                    if (images.isNotEmpty()) {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${images.size}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    } else {
                        Text("Фото")
                    }
                },
                actions = {
                    if (images.isNotEmpty()) {
                        Text(
                            text = images[pagerState.currentPage].name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 12.dp),
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
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isLoading -> CircularProgressIndicator(color = Color.White)
                images.isEmpty() -> Text(
                    text = "Изображения не найдены",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                else -> HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    ImagePage(item = images[page], viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun ImagePage(
    item: CloudItem,
    viewModel: ImageGalleryViewModel,
) {
    var url by remember(item.id) { mutableStateOf<String?>(null) }
    var loading by remember(item.id) { mutableStateOf(true) }

    LaunchedEffect(item.id) {
        url = viewModel.resolveUrl(item)
        loading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator(color = Color.White)
            url == null -> Text(
                text = "Не удалось загрузить",
                color = Color.White.copy(alpha = 0.6f),
            )
            else -> AsyncImage(
                model = url,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
