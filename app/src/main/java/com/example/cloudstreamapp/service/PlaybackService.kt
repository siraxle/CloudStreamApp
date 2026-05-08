package com.example.cloudstreamapp.service

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var simpleCache: SimpleCache
    @Inject lateinit var playlistRepo: PlaylistRepositoryImpl

    private var mediaSession: MediaSession? = null

    companion object {
        /** Set by PlayerViewModel when a playlist is loaded; cleared when playback stops or switches. */
        @Volatile var currentPlaylistId: String? = null
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val artworkDir by lazy { File(cacheDir, "artwork").also { it.mkdirs() } }

    override fun onCreate() {
        super.onCreate()

        val upstreamFactory = OkHttpDataSource.Factory(okHttpClient)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                60_000,
                2_500,
                5_000,
            )
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val artworkData = mediaMetadata.artworkData ?: return
                val currentItem = player.currentMediaItem ?: return
                if (currentItem.mediaMetadata.artworkUri != null) return
                val idx = player.currentMediaItemIndex
                if (idx < 0) return

                val safeId = currentItem.mediaId
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    .take(64)
                    .ifEmpty { "track_$idx" }

                serviceScope.launch {
                    try {
                        val artFile = File(artworkDir, "$safeId.jpg")
                        if (!artFile.exists()) artFile.writeBytes(artworkData)
                        val artUri = Uri.fromFile(artFile)
                        withContext(Dispatchers.Main) {
                            val curIdx = player.currentMediaItemIndex
                            if (curIdx < 0 || curIdx >= player.mediaItemCount) return@withContext
                            val cur = player.getMediaItemAt(curIdx)
                            if (cur.mediaMetadata.artworkUri != null) return@withContext
                            player.replaceMediaItem(
                                curIdx,
                                cur.buildUpon()
                                    .setMediaMetadata(
                                        cur.mediaMetadata.buildUpon()
                                            .setArtworkUri(artUri)
                                            .build()
                                    )
                                    .build()
                            )
                        }
                    } catch (_: Exception) {}
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()

        serviceScope.launch {
            playlistRepo.deletedPlaylistFlow.collect { deletedId ->
                if (deletedId == currentPlaylistId) {
                    currentPlaylistId = null
                    withContext(Dispatchers.Main) {
                        player.clearMediaItems()
                        player.stop()
                    }
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
