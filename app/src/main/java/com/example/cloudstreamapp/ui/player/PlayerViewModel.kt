package com.example.cloudstreamapp.ui.player

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.cloudstreamapp.service.PlaybackService
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private var controller: MediaController? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    init {
        connectToService()
        startProgressUpdater()
    }

    private fun connectToService() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener({
            controller = future.get()
            controller?.addListener(playerListener)
            updateState()
        }, MoreExecutors.directExecutor())
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
        _playerState.value = PlayerState(
            isPlaying = c.isPlaying,
            title = c.mediaMetadata.title?.toString(),
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
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .build()
            )
            .build()
        controller?.setMediaItem(mediaItem)
        controller?.prepare()
        controller?.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun skipToNext() { controller?.seekToNextMediaItem() }

    fun skipToPrevious() { controller?.seekToPreviousMediaItem() }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        controller?.release()
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
