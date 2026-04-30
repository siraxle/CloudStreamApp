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
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.domain.model.CacheStatus
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudType
import com.example.cloudstreamapp.domain.usecase.GetStreamUrlUseCase
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
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val getStreamUrl: GetStreamUrlUseCase,
    private val playlistRepo: PlaylistRepositoryImpl,
) : ViewModel() {

    private var controller: MediaController? = null
    // Buffered while controller is still connecting (single-file mode)
    private var pendingPlay: Pair<String, String?>? = null
    // Buffered while controller is still connecting (playlist mode)
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
        if (savedStateHandle.get<String>("playlistId") != null) {
            fetchAndPlayPlaylist()
        } else {
            fetchAndPlay()
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
                pendingPlay?.let { (url, title) ->
                    enqueuePlay(url, title)
                    pendingPlay = null
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

    // ── Single-file mode (Browser / Search) ──────────────────────────────────

    private fun fetchAndPlay() {
        val cloudTypeStr = savedStateHandle.get<String>("cloudType") ?: return
        val sourceUrl = savedStateHandle.get<String>("encodedSourceUrl") ?: return
        val itemPath = savedStateHandle.get<String>("encodedItemPath") ?: return
        val itemName = savedStateHandle.get<String>("encodedItemName") ?: ""

        _playerState.value = _playerState.value.copy(title = itemName)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cloudType = CloudType.valueOf(cloudTypeStr)
                val item = CloudItem(
                    id = UUID.nameUUIDFromBytes("$sourceUrl:$itemPath".toByteArray()).toString(),
                    name = itemName,
                    path = CloudPath(
                        sourceId = sourceUrl,
                        relativePath = itemPath,
                        cloudType = cloudType,
                    ),
                    type = CloudItem.ItemType.FILE,
                    cacheStatus = CacheStatus.REMOTE,
                )
                val url = getStreamUrl(item) ?: error("Провайдер не вернул URL для $itemName")
                withContext(Dispatchers.Main) { playUrl(url, itemName) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = e.message ?: "Не удалось получить ссылку"
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
                // Load all tracks from Room
                val pairs = playlistRepo.getItemsWithMetadata(playlistId).first()
                val cloudItems = pairs.mapNotNull { (_, cloudItem) -> cloudItem }

                if (cloudItems.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _isLoadingPlaylist.value = false
                        _error.value = "Плейлист пуст"
                    }
                    return@launch
                }

                // Show title immediately for responsiveness
                val firstItem = cloudItems.getOrElse(startIndex) { cloudItems[0] }
                withContext(Dispatchers.Main) {
                    _playerState.value = _playerState.value.copy(title = firstItem.name)
                }

                // Resolve all stream URLs in parallel
                val mediaItems: List<MediaItem> = coroutineScope {
                    cloudItems.map { item ->
                        async {
                            try {
                                val url = getStreamUrl(item) ?: return@async null
                                MediaItem.Builder()
                                    .setUri(url)
                                    .setMediaId(item.id)
                                    .setCustomCacheKey(item.id)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder().setTitle(item.name).build()
                                    )
                                    .build()
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }.awaitAll()
                }.filterNotNull()

                if (mediaItems.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _isLoadingPlaylist.value = false
                        _error.value = "Не удалось получить ссылки для воспроизведения"
                    }
                    return@launch
                }

                // Adjust startIndex after filtering failed items
                val actualStart = startIndex.coerceIn(0, mediaItems.size - 1)

                withContext(Dispatchers.Main) {
                    _isLoadingPlaylist.value = false
                    setPlaylistAndPlay(mediaItems, actualStart)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isLoadingPlaylist.value = false
                    _error.value = e.message ?: "Не удалось загрузить плейлист"
                }
            }
        }
    }

    private fun setPlaylistAndPlay(items: List<MediaItem>, startIndex: Int) {
        val c = controller
        if (c == null) {
            pendingPlaylist = Pair(items, startIndex)
            return
        }
        enqueuePlaylist(items, startIndex)
    }

    private fun enqueuePlaylist(items: List<MediaItem>, startIndex: Int) {
        val c = controller ?: return
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
        _playerState.value = _playerState.value.copy(hasMedia = true)
    }

    // ── Common player logic ───────────────────────────────────────────────────

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
        val c = controller
        if (c == null) {
            pendingPlay = Pair(url, title)
            return
        }
        enqueuePlay(url, title)
    }

    private fun enqueuePlay(url: String, title: String?) {
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        controller?.setMediaItem(mediaItem)
        controller?.prepare()
        controller?.play()
        if (title != null) {
            _playerState.value = _playerState.value.copy(title = title, hasMedia = true)
        }
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
