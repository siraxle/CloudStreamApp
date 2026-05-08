package com.example.cloudstreamapp.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.cloudstreamapp.core.utils.toFormattedDuration
import com.example.cloudstreamapp.domain.model.CloudItem
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onOpenGallery: ((cloudType: String, sourceUrl: String, folderPath: String) -> Unit)? = null,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.playerState.collectAsState()
    val player by viewModel.player.collectAsState()
    val isLoading by viewModel.isLoadingPlaylist.collectAsState()
    val error by viewModel.error.collectAsState()
    val folderInfo by viewModel.folderInfo.collectAsState()
    val coverImages by viewModel.coverImages.collectAsState()
    val embeddedArtUri by viewModel.embeddedArtUri.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar("Ошибка: $error")
            viewModel.dismissError()
        }
    }

    if (state.hasVideo && player != null) {
        VideoPlayerContent(
            player = player!!,
            state = state,
            onBack = onBack,
            onSeekBy = viewModel::seekBy,
            onSeekTo = viewModel::seekTo,
            onTogglePlayPause = viewModel::togglePlayPause,
            onSkipNext = viewModel::skipToNext,
            onSkipPrevious = viewModel::skipToPrevious,
            snackbarHostState = snackbarHostState,
        )
    } else {
        AudioPlayerContent(
            state = state,
            isLoading = isLoading,
            coverImages = coverImages,
            embeddedArtUri = embeddedArtUri,
            onResolveCoverUrl = viewModel::resolveCoverUrl,
            onBack = onBack,
            onTogglePlayPause = viewModel::togglePlayPause,
            onSeekTo = viewModel::seekTo,
            onSkipNext = viewModel::skipToNext,
            onSkipPrevious = viewModel::skipToPrevious,
            snackbarHostState = snackbarHostState,
            showGalleryButton = onOpenGallery != null && folderInfo?.hasImages == true,
            onOpenGallery = {
                folderInfo?.let { info ->
                    onOpenGallery?.invoke(info.cloudType, info.sourceUrl, info.folderPath)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioPlayerContent(
    state: PlayerViewModel.PlayerState,
    isLoading: Boolean,
    coverImages: List<CloudItem>,
    embeddedArtUri: String?,
    onResolveCoverUrl: suspend (CloudItem) -> String?,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    snackbarHostState: SnackbarHostState,
    showGalleryButton: Boolean = false,
    onOpenGallery: () -> Unit = {},
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = { Text("Плеер") },
                actions = {
                    if (showGalleryButton) {
                        IconButton(onClick = onOpenGallery) {
                            Icon(Icons.Default.Collections, contentDescription = "Фото")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            // Cover area — swipeable pager of cloud images, or embedded art, or placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CoverArea(
                    coverImages = coverImages,
                    embeddedArtUri = embeddedArtUri,
                    onResolveCoverUrl = onResolveCoverUrl,
                    modifier = Modifier.fillMaxSize(),
                )
                if (isLoading) {
                    CircularProgressIndicator()
                }
            }

            // Track info
            Text(
                text = state.title ?: "Без названия",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            state.artist?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (state.durationMs > 0) {
                val safeMax = state.durationMs.coerceAtLeast(1L).toFloat()
                Slider(
                    value = state.positionMs.toFloat().coerceIn(0f, safeMax),
                    onValueChange = { onSeekTo(it.toLong()) },
                    valueRange = 0f..safeMax,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = state.positionMs.toFormattedDuration(),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = state.durationMs.toFormattedDuration(),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onSkipPrevious) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Предыдущий",
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Пауза" else "Играть",
                        modifier = Modifier.size(64.dp),
                    )
                }
                IconButton(onClick = onSkipNext) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Следующий",
                        modifier = Modifier.size(40.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverArea(
    coverImages: List<CloudItem>,
    embeddedArtUri: String?,
    onResolveCoverUrl: suspend (CloudItem) -> String?,
    modifier: Modifier = Modifier,
) {
    when {
        coverImages.isNotEmpty() -> {
            // key resets pager to page 0 whenever the image set changes (different folder)
            key(coverImages.first().id) {
                val pagerState = rememberPagerState(pageCount = { coverImages.size })
                Box(modifier = modifier) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        CoverImagePage(item = coverImages[page], onResolveCoverUrl = onResolveCoverUrl)
                    }
                    if (coverImages.size > 1) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val dotCount = minOf(coverImages.size, 12)
                            repeat(dotCount) { idx ->
                                val isActive = idx == pagerState.currentPage
                                Box(
                                    modifier = Modifier
                                        .size(if (isActive) 8.dp else 6.dp)
                                        .background(
                                            if (isActive) Color.White
                                            else Color.White.copy(alpha = 0.45f),
                                            CircleShape,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
        embeddedArtUri != null -> AsyncImage(
            model = embeddedArtUri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
        else -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
        }
    }
}

@Composable
private fun CoverImagePage(
    item: CloudItem,
    onResolveCoverUrl: suspend (CloudItem) -> String?,
) {
    var url by remember(item.id) { mutableStateOf<String?>(null) }
    var loading by remember(item.id) { mutableStateOf(true) }
    LaunchedEffect(item.id) {
        url = onResolveCoverUrl(item)
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
            url != null -> AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun VideoPlayerContent(
    player: Player,
    state: PlayerViewModel.PlayerState,
    onBack: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    var controlsVisible by remember { mutableStateOf(true) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var gestureHint by remember { mutableStateOf<String?>(null) }

    var dragStartX by remember { mutableFloatStateOf(0f) }
    var dragStartY by remember { mutableFloatStateOf(0f) }
    var dragStartPositionMs by remember { mutableLongStateOf(0L) }
    var dragStartVolume by remember { mutableIntStateOf(0) }
    var dragStartBrightness by remember { mutableFloatStateOf(0.5f) }
    var dragDirection by remember { mutableStateOf<DragDirection?>(null) }

    LaunchedEffect(gestureHint) {
        if (gestureHint != null) {
            delay(1200)
            gestureHint = null
        }
    }

    LaunchedEffect(controlsVisible, state.isPlaying) {
        if (controlsVisible && state.isPlaying) {
            delay(3000)
            controlsVisible = false
        }
    }

    DisposableEffect(Unit) {
        val window = activity.window
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    setResizeMode(resizeMode)
                }
            },
            update = { view ->
                view.player = player
                view.setResizeMode(resizeMode)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Gesture capture layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state.durationMs) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { offset ->
                            val delta = if (offset.x < size.width / 2) -10_000L else 10_000L
                            onSeekBy(delta)
                            gestureHint = if (delta < 0) "−10 сек" else "+10 сек"
                            controlsVisible = true
                        },
                    )
                }
                .pointerInput(state.durationMs, maxVolume) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStartX = offset.x
                            dragStartY = offset.y
                            dragDirection = null
                            dragStartPositionMs = state.positionMs
                            dragStartVolume =
                                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            dragStartBrightness =
                                activity.window.attributes.screenBrightness
                                    .takeIf { it >= 0f } ?: 0.5f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (dragDirection == null) {
                                dragDirection =
                                    if (abs(dragAmount.x) > abs(dragAmount.y))
                                        DragDirection.Horizontal
                                    else
                                        DragDirection.Vertical
                            }
                            val totalX = change.position.x - dragStartX
                            val totalY = change.position.y - dragStartY
                            when (dragDirection!!) {
                                DragDirection.Horizontal -> {
                                    if (state.durationMs > 0) {
                                        val fraction = totalX / size.width.toFloat()
                                        val newMs =
                                            (dragStartPositionMs + fraction * state.durationMs)
                                                .toLong()
                                                .coerceIn(0L, state.durationMs)
                                        onSeekTo(newMs)
                                        gestureHint = newMs.toFormattedDuration()
                                    }
                                }
                                DragDirection.Vertical -> {
                                    val fraction = -totalY / size.height.toFloat()
                                    if (dragStartX < size.width / 2) {
                                        val newBrightness =
                                            (dragStartBrightness + fraction).coerceIn(0.01f, 1f)
                                        val params = activity.window.attributes
                                        params.screenBrightness = newBrightness
                                        activity.window.attributes = params
                                        gestureHint = "☀ ${(newBrightness * 100).toInt()}%"
                                    } else {
                                        val newVol =
                                            (dragStartVolume + fraction * maxVolume)
                                                .toInt()
                                                .coerceIn(0, maxVolume)
                                        audioManager.setStreamVolume(
                                            AudioManager.STREAM_MUSIC,
                                            newVol,
                                            0,
                                        )
                                        gestureHint =
                                            "🔊 ${(newVol * 100f / maxVolume).toInt()}%"
                                    }
                                }
                            }
                        },
                        onDragEnd = { dragDirection = null },
                        onDragCancel = { dragDirection = null },
                    )
                },
        )

        // Gesture feedback badge
        gestureHint?.let { hint ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(hint, color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }

        // Controls overlay
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
            ) {
                // Top bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = state.title ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                    )
                    IconButton(onClick = {
                        resizeMode =
                            if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT)
                                AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else
                                AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }) {
                        Icon(
                            Icons.Default.AspectRatio,
                            contentDescription = "Масштаб",
                            tint = Color.White,
                        )
                    }
                }

                // Bottom controls
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    if (state.durationMs > 0) {
                        val safeMax = state.durationMs.coerceAtLeast(1L).toFloat()
                        Slider(
                            value = state.positionMs.toFloat().coerceIn(0f, safeMax),
                            onValueChange = { onSeekTo(it.toLong()) },
                            valueRange = 0f..safeMax,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = state.positionMs.toFormattedDuration(),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                            )
                            Text(
                                text = state.durationMs.toFormattedDuration(),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(
                            16.dp,
                            Alignment.CenterHorizontally,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IconButton(onClick = onSkipPrevious) {
                            Icon(
                                Icons.Default.SkipPrevious,
                                contentDescription = "Предыдущий",
                                modifier = Modifier.size(36.dp),
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = onTogglePlayPause) {
                            Icon(
                                if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (state.isPlaying) "Пауза" else "Играть",
                                modifier = Modifier.size(56.dp),
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = onSkipNext) {
                            Icon(
                                Icons.Default.SkipNext,
                                contentDescription = "Следующий",
                                modifier = Modifier.size(36.dp),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private enum class DragDirection { Horizontal, Vertical }
