package com.example.cloudstreamapp.ui.player

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.cloudstreamapp.core.cache.MediaCacheManager
import com.example.cloudstreamapp.core.utils.isMediaFile
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.domain.model.CacheStatus
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudType
import com.example.cloudstreamapp.domain.usecase.GetStreamUrlUseCase
import com.example.cloudstreamapp.domain.usecase.ListFolderUseCase
import com.example.cloudstreamapp.service.PlaybackService
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val getStreamUrl: GetStreamUrlUseCase,
    private val listFolder: ListFolderUseCase,
    private val playlistRepo: PlaylistRepositoryImpl,
    private val cacheManager: MediaCacheManager,
) : ViewModel() {

    private var controller: MediaController? = null
    private var pendingMediaItem: MediaItem? = null
    private var pendingPlaylist: Pair<List<MediaItem>, Int>? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _isLoadingPlaylist = MutableStateFlow(false)
    val isLoadingPlaylist: StateFlow<Boolean> = _isLoadingPlaylist.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    init {
        connectToService()
        startProgressUpdater()
        when {
            savedStateHandle.get<String>("playlistId") != null -> fetchAndPlayPlaylist()
            savedStateHandle.get<String>("encodedFolderPath") != null -> fetchAndPlayFolder()
            else -> fetchAndPlay()
        }
    }

    private fun connectToService() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener({
            try {
                controller = future.get()
                controller?.addListener(playerListener)
                _player.value = controller
                updateState()
                pendingMediaItem?.let { item ->
                    playMediaItem(item)
                    pendingMediaItem = null
                }
                pendingPlaylist?.let { (items, index) ->
                    enqueuePlaylist(items, index)
                    pendingPlaylist = null
                }
            } catch (e: Exception) {
                _error.value = "Не удалось подключиться к плееру: ${e.message}"
            }
        }, MoreExecutors.directExecutor())
    }

    // ── Single-file mode (Search) ─────────────────────────────────────────────

    private fun fetchAndPlay() {
        val cloudTypeStr = savedStateHandle.get<String>("cloudType") ?: return
        val sourceUrl = savedStateHandle.get<String>("encodedSourceUrl") ?: return
        val itemPath = savedStateHandle.get<String>("encodedItemPath") ?: return
        val itemName = savedStateHandle.get<String>("encodedItemName") ?: ""
        val mediaId = savedStateHandle.get<String>("encodedMediaId") ?: return

        _playerState.value = _playerState.value.copy(title = itemName)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cloudType = CloudType.valueOf(cloudTypeStr)
                val item = CloudItem(
                    id = mediaId,
                    name = itemName,
                    path = CloudPath(
                        sourceId = sourceUrl,
                        relativePath = itemPath,
                        cloudType = cloudType,
                    ),
                    type = CloudItem.ItemType.FILE,
                )
                val url = runCatching { getStreamUrl(item) }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (url != null) {
                        playMediaItem(buildOnlineMediaItem(item, url))
                    } else if (cacheManager.getCacheStatus(mediaId, null) == CacheStatus.CACHED) {
                        playMediaItem(buildOfflineMediaItem(mediaId, itemName))
                    } else {
                        _error.value = "Нет соединения, файл не скачан для офлайн-воспроизведения"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = e.message ?: "Не удалось получить ссылку"
                }
            }
        }
    }

    // ── Folder mode (Browser) — loads all media files in the folder as a queue ─

    private fun fetchAndPlayFolder() {
        val cloudTypeStr = savedStateHandle.get<String>("cloudType") ?: return
        val sourceUrl = savedStateHandle.get<String>("encodedSourceUrl") ?: return
        val folderPath = savedStateHandle.get<String>("encodedFolderPath") ?: return
        val mediaId = savedStateHandle.get<String>("encodedMediaId") ?: return

        _isLoadingPlaylist.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cloudType = CloudType.valueOf(cloudTypeStr)
                val path = CloudPath(
                    sourceId = sourceUrl,
                    relativePath = folderPath,
                    cloudType = cloudType,
                )

                val allItems = listFolder(path).first()
                val mediaFiles = allItems
                    .filter { it.type == CloudItem.ItemType.FILE && it.name.isMediaFile() }
                    .sortedBy { it.name.lowercase() }

                if (mediaFiles.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _isLoadingPlaylist.value = false
                        _error.value = "В папке нет медиафайлов"
                    }
                    return@launch
                }

                val clickedItem = mediaFiles.firstOrNull { it.id == mediaId } ?: mediaFiles.first()
                withContext(Dispatchers.Main) {
                    _playerState.value = _playerState.value.copy(title = clickedItem.name)
                }

                // Resolve all stream URLs in parallel
                val mediaItems: List<MediaItem> = coroutineScope {
                    mediaFiles.map { item ->
                        async {
                            val url = runCatching { getStreamUrl(item) }.getOrNull()
                            when {
                                url != null -> buildOnlineMediaItem(item, url)
                                cacheManager.getCacheStatus(item.id, item.sizeBytes) == CacheStatus.CACHED ->
                                    buildOfflineMediaItem(item.id, item.name)
                                else -> null
                            }
                        }
                    }.awaitAll()
                }.filterNotNull()

                if (mediaItems.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _isLoadingPlaylist.value = false
                        _error.value = "Нет доступных треков: подключитесь к сети или скачайте треки заранее"
                    }
                    return@launch
                }

                val startIndex = mediaItems.indexOfFirst { it.mediaId == mediaId }.coerceAtLeast(0)

                withContext(Dispatchers.Main) {
                    _isLoadingPlaylist.value = false
                    val c = controller
                    if (c == null) {
                        pendingPlaylist = Pair(mediaItems, startIndex)
                    } else {
                        enqueuePlaylist(mediaItems, startIndex)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isLoadingPlaylist.value = false
                    _error.value = e.message ?: "Не удалось загрузить треки папки"
                }
            }
        }
    }

    // ── Playlist mode ─────────────────────────────────────────────────────────

    private fun fetchAndPlayPlaylist() {
        val playlistId = savedStateHandle.get<String>("playlistId") ?: return
        val startIndex = savedStateHandle.get<Int>("startIndex") ?: 0

        _isLoadingPlaylist.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pairs = playlistRepo.getItemsWithMetadata(playlistId).first()
                val cloudItems = pairs.mapNotNull { (_, cloudItem) -> cloudItem }

                if (cloudItems.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _isLoadingPlaylist.value = false
                        _error.value = "Плейлист пуст"
                    }
                    return@launch
                }

                val firstItem = cloudItems.getOrElse(startIndex) { cloudItems[0] }
                withContext(Dispatchers.Main) {
                    _playerState.value = _playerState.value.copy(title = firstItem.name)
                }

                // Resolve all stream URLs in parallel; fall back to cache for offline items
                val mediaItems: List<MediaItem> = coroutineScope {
                    cloudItems.map { item ->
                        async {
                            val url = runCatching { getStreamUrl(item) }.getOrNull()
                            when {
                                url != null -> buildOnlineMediaItem(item, url)
                                item.cacheStatus == CacheStatus.CACHED ->
                                    buildOfflineMediaItem(item.id, item.name)
                                else -> null
                            }
                        }
                    }.awaitAll()
                }.filterNotNull()

                if (mediaItems.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _isLoadingPlaylist.value = false
                        _error.value = "Нет доступных треков: подключитесь к сети или скачайте треки заранее"
                    }
                    return@launch
                }

                val actualStart = startIndex.coerceIn(0, mediaItems.size - 1)

                withContext(Dispatchers.Main) {
                    _isLoadingPlaylist.value = false
                    val c = controller
                    if (c == null) {
                        pendingPlaylist = Pair(mediaItems, actualStart)
                    } else {
                        enqueuePlaylist(mediaItems, actualStart)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isLoadingPlaylist.value = false
                    _error.value = e.message ?: "Не удалось загрузить плейлист"
                }
            }
        }
    }

    // ── MediaItem builders ────────────────────────────────────────────────────

    private fun buildOnlineMediaItem(item: CloudItem, url: String) =
        MediaItem.Builder()
            .setUri(url)
            .setMediaId(item.id)
            .setCustomCacheKey(item.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.name).build())
            .build()

    /** Placeholder URI — CacheDataSource serves data from SimpleCache by CustomCacheKey. */
    private fun buildOfflineMediaItem(mediaId: String, title: String) =
        MediaItem.Builder()
            .setUri("https://offline.cache/$mediaId")
            .setMediaId(mediaId)
            .setCustomCacheKey(mediaId)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()

    // ── Common player logic ───────────────────────────────────────────────────

    private fun playMediaItem(mediaItem: MediaItem) {
        val c = controller
        if (c == null) {
            pendingMediaItem = mediaItem
            return
        }
        c.setMediaItem(mediaItem)
        c.prepare()
        c.play()
        val title = mediaItem.mediaMetadata.title?.toString()
        if (title != null) {
            _playerState.value = _playerState.value.copy(title = title, hasMedia = true)
        }
    }

    private fun enqueuePlaylist(items: List<MediaItem>, startIndex: Int) {
        val c = controller ?: return
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
        _playerState.value = _playerState.value.copy(hasMedia = true)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateState()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateState()
        override fun onPlaybackStateChanged(playbackState: Int) = updateState()
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = updateState()
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _playerState.value = _playerState.value.copy(hasVideo = videoSize.width > 0)
        }
    }

    private fun updateState() {
        val c = controller ?: return
        _playerState.value = _playerState.value.copy(
            isPlaying = c.isPlaying,
            title = c.mediaMetadata.title?.toString() ?: _playerState.value.title,
            artist = c.mediaMetadata.artist?.toString(),
            durationMs = c.duration.coerceAtLeast(0),
            positionMs = c.currentPosition.coerceAtLeast(0),
            hasMedia = c.mediaItemCount > 0,
        )
    }

    private fun startProgressUpdater() {
        viewModelScope.launch {
            while (true) {
                delay(500)
                val c = controller ?: continue
                if (c.isPlaying) {
                    _playerState.value = _playerState.value.copy(
                        positionMs = c.currentPosition.coerceAtLeast(0),
                        durationMs = c.duration.coerceAtLeast(0),
                    )
                }
            }
        }
    }

    fun playUrl(url: String, title: String? = null) {
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        playMediaItem(mediaItem)
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0L, c.duration.coerceAtLeast(0L)))
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }
    fun skipToNext() { controller?.seekToNextMediaItem() }
    fun skipToPrevious() { controller?.seekToPreviousMediaItem() }
    fun dismissError() { _error.value = null }

    override fun onCleared() {
        _player.value = null
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        super.onCleared()
    }

    data class PlayerState(
        val isPlaying: Boolean = false,
        val title: String? = null,
        val artist: String? = null,
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val hasMedia: Boolean = false,
        val hasVideo: Boolean = false,
    )
}
