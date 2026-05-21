package com.example.cloudstreamapp.ui.player

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
class MiniPlayerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private var controller: MediaController? = null

    private val _state = MutableStateFlow(MiniPlayerState())
    val state: StateFlow<MiniPlayerState> = _state.asStateFlow()

    init {
        connectToService()
        startProgressUpdater()
    }

    private fun connectToService() {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener({
            try {
                controller = future.get()
                controller?.addListener(playerListener)
                updateState()
            } catch (_: Exception) { }
        }, MoreExecutors.directExecutor())
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateState()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateState()
        override fun onPlaybackStateChanged(playbackState: Int) = updateState()
    }

    private fun updateState() {
        val c = controller ?: return
        _state.value = MiniPlayerState(
            isPlaying = c.isPlaying,
            title = c.mediaMetadata.title?.toString(),
            artist = c.mediaMetadata.artist?.toString(),
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.coerceAtLeast(0),
            hasMedia = c.mediaItemCount > 0,
        )
    }

    private fun startProgressUpdater() {
        viewModelScope.launch {
            while (true) {
                delay(500)
                val c = controller ?: continue
                if (c.isPlaying) updateState()
            }
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }
    fun skipToNext() { controller?.seekToNextMediaItem() }
    fun skipToPrevious() { controller?.seekToPreviousMediaItem() }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        super.onCleared()
    }

    data class MiniPlayerState(
        val isPlaying: Boolean = false,
        val title: String? = null,
        val artist: String? = null,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val hasMedia: Boolean = false,
    )
}
