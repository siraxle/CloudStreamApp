package com.example.cloudstreamapp.ui.player

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val getStreamUrl: GetStreamUrlUseCase,
) : ViewModel() {

    private var controller: MediaController? = null
    // Buffered while controller is still connecting
    private var pendingPlay: Pair<String, String?>? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        connectToService()
        startProgressUpdater()
        fetchAndPlay()
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
                updateState()
                pendingPlay?.let { (url, title) ->
                    enqueuePlay(url, title)
                    pendingPlay = null
                }
            } catch (e: Exception) {
                _error.value = "Не удалось подключиться к плееру: ${e.message}"
            }
        }, MoreExecutors.directExecutor())
    }

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
                withContext(Dispatchers.Main) {
                    playUrl(url, itemName)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = e.message ?: "Не удалось получить ссылку"
                }
            }
        }
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
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).build()
            )
            .build()
        controller?.setMediaItem(mediaItem)
        controller?.prepare()
        controller?.play()
        if (title != null) {
            _playerState.value = _playerState.value.copy(title = title, hasMedia = true)
        }
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
    )
}
