package com.example.cloudstreamapp.ui.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.net.Uri
import com.example.cloudstreamapp.core.cache.MediaCacheManager
import com.example.cloudstreamapp.data.torrent.download.TorrentCacheManager
import com.example.cloudstreamapp.data.torrent.engine.LibtorrentEngine
import com.example.cloudstreamapp.core.utils.isImageFile
import com.example.cloudstreamapp.core.utils.isMediaFile
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.data.torrent.saved.SavedTorrentRepository
import com.example.cloudstreamapp.domain.model.CacheStatus
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudType
import com.example.cloudstreamapp.domain.torrent.CacheProgress
import com.example.cloudstreamapp.domain.torrent.TorrentResult
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.cloudstreamapp.data.torrent.provider.extractInfoHash
import com.example.cloudstreamapp.domain.port.SettingsRepositoryPort
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val getStreamUrl: GetStreamUrlUseCase,
    private val listFolder: ListFolderUseCase,
    private val playlistRepo: PlaylistRepositoryImpl,
    private val cacheManager: MediaCacheManager,
    private val settings: SettingsRepositoryPort,
    private val torrentEngine: LibtorrentEngine,
    private val torrentCacheManager: TorrentCacheManager,
    private val savedTorrentRepo: SavedTorrentRepository,
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

    val playbackSpeed: StateFlow<Float> = settings.playbackSpeed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1.0f)

    private val _folderInfo = MutableStateFlow<FolderInfo?>(null)
    val folderInfo: StateFlow<FolderInfo?> = _folderInfo.asStateFlow()

    val isTorrentStream: Boolean = savedStateHandle.get<String>("cloudType") == "TORRENT"

    private val _torrentProgress = MutableStateFlow<Float?>(null)
    val torrentProgress: StateFlow<Float?> = _torrentProgress.asStateFlow()

    private val _torrentPreBuffer = MutableStateFlow<TorrentPreBufferState>(TorrentPreBufferState.Idle)
    val torrentPreBuffer: StateFlow<TorrentPreBufferState> = _torrentPreBuffer.asStateFlow()

    // All image files found in the current folder — URLs resolved on-demand in the UI
    private val _coverImages = MutableStateFlow<List<CloudItem>>(emptyList())
    val coverImages: StateFlow<List<CloudItem>> = _coverImages.asStateFlow()

    // Embedded art URI set by PlaybackService (file:// path written after track headers are parsed)
    private val _embeddedArtUri = MutableStateFlow<String?>(null)
    val embeddedArtUri: StateFlow<String?> = _embeddedArtUri.asStateFlow()

    // Folder key of last scanned folder — prevents redundant network scans for same-folder tracks
    @Volatile private var lastScannedFolderKey: String? = null

    // Populated in playlist mode: mediaId → CloudItem for per-track folder scanning on transition
    private val mediaIdToCloudItem = HashMap<String, CloudItem>()

    init {
        connectToService()
        startProgressUpdater()
        when {
            savedStateHandle.get<String>("playlistId") != null -> fetchAndPlayPlaylist()
            savedStateHandle.get<String>("encodedFolderPath") != null -> fetchAndPlayFolder()
            else -> fetchAndPlay()
        }
        if (isTorrentStream) {
            // infoHash is embedded in the magnetUri (sourceUrl), not in folderPath.
            // folderPath now holds the subfolder path within the torrent ("" = root).
            val sourceUrl = savedStateHandle.get<String>("encodedSourceUrl") ?: ""
            val infoHash = extractInfoHash(sourceUrl)
                ?: if (sourceUrl.startsWith("torrent:")) sourceUrl.removePrefix("torrent:") else ""
            if (infoHash.isNotEmpty()) {
                viewModelScope.launch {
                    torrentEngine.downloadProgressFlow(infoHash).collect { progress ->
                        _torrentProgress.value = progress
                    }
                }
            }
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

    // ── MediaItem resolvers ───────────────────────────────────────────────────
    // Check cache FIRST — cached items skip the network call entirely.

    /** Resolve a playlist item: uses item.cacheStatus (set by getItemsWithMetadata). */
    private suspend fun resolvePlaylistItem(item: CloudItem): MediaItem? {
        // LOCAL items are files on disk — always use file:// URI via LocalFileCloudProvider,
        // never route to ExoPlayer SimpleCache (which has no data for these keys).
        if (item.path.cloudType == CloudType.LOCAL) {
            val url = runCatching { getStreamUrl(item) }.getOrNull() ?: return null
            return buildOnlineMediaItem(item, url)
        }
        if (item.cacheStatus == CacheStatus.CACHED) {
            return buildOfflineMediaItem(item.id, item.name, item.path.toExtrasBundle())
        }
        val url = runCatching { getStreamUrl(item) }.getOrNull() ?: return null
        return buildOnlineMediaItem(item, url)
    }

    /** Resolve a folder item: checks SimpleCache directly (cacheStatus not set by listFolder). */
    private suspend fun resolveFolderItem(item: CloudItem): MediaItem? {
        if (cacheManager.getCacheStatus(item.id, item.sizeBytes) == CacheStatus.CACHED) {
            return buildOfflineMediaItem(item.id, item.name, item.path.toExtrasBundle())
        }
        val url = runCatching { getStreamUrl(item) }.getOrNull() ?: return null
        return buildOnlineMediaItem(item, url)
    }

    // ── Single-file mode ──────────────────────────────────────────────────────

    private fun fetchAndPlay() {
        PlaybackService.currentPlaylistId = null
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
                    path = CloudPath(sourceId = sourceUrl, relativePath = itemPath, cloudType = cloudType),
                    type = CloudItem.ItemType.FILE,
                )
                val url = runCatching { getStreamUrl(item) }.getOrNull()
                if (url != null) {
                    val mediaItem = buildOnlineMediaItem(item, url)
                    if (isTorrentStream) waitForTorrentBuffer(mediaId)
                    withContext(Dispatchers.Main) {
                        _torrentPreBuffer.value = TorrentPreBufferState.Idle
                        playMediaItem(mediaItem)
                    }
                } else if (cacheManager.getCacheStatus(mediaId, null) == CacheStatus.CACHED) {
                    withContext(Dispatchers.Main) {
                        playMediaItem(buildOfflineMediaItem(mediaId, itemName, item.path.toExtrasBundle()))
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _error.value = "Нет соединения, файл не скачан для офлайн-воспроизведения"
                    }
                }
                withContext(Dispatchers.Main) { triggerCoverScan(item.path) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _torrentPreBuffer.value = TorrentPreBufferState.Idle
                    _error.value = e.message ?: "Не удалось получить ссылку"
                }
            }
        }
    }

    /**
     * Boosts priority for the file's initial pieces and suspends until
     * [MIN_INITIAL_PIECES] of them are downloaded. Shows [TorrentPreBufferState.Buffering]
     * progress in the UI while waiting so the player never starts on empty data.
     */
    private suspend fun waitForTorrentBuffer(mediaId: String) {
        val parts = mediaId.split(":")
        if (parts.size < 2) return
        val infoHash = parts[0]
        val fileIndex = parts[1].toIntOrNull() ?: return

        val folderPath = torrentEngine.listFiles(infoHash)
            .find { it.index == fileIndex }
            ?.relativePath?.substringBeforeLast("/", "") ?: ""
        torrentCacheManager.cacheFolder(infoHash, folderPath)
        autoSaveTorrentIfAbsent(infoHash, folderPath)

        // File is already fully on disk — skip the pre-buffer spinner. Just ensure the file
        // is not at IGNORE priority so the HTTP server can serve pieces while the hash check
        // completes in the background.
        if (torrentCacheManager.getProgress(infoHash, fileIndex) is CacheProgress.Cached) {
            torrentEngine.enableFileDownload(infoHash, fileIndex)
            return
        }

        torrentEngine.boostFilePriority(infoHash, fileIndex)
        withContext(Dispatchers.Main) {
            _torrentPreBuffer.value = TorrentPreBufferState.Buffering(0f)
        }
        torrentEngine.waitForInitialPiecesFlow(infoHash, fileIndex, MIN_INITIAL_PIECES)
            .collect { progress ->
                withContext(Dispatchers.Main) {
                    _torrentPreBuffer.value = TorrentPreBufferState.Buffering(progress)
                }
            }
    }

    private suspend fun autoSaveTorrentIfAbsent(infoHash: String, folderPath: String) {
        runCatching {
            if (savedTorrentRepo.getAllHashes().first().contains(infoHash)) return
            val magnetUri = savedStateHandle.get<String>("encodedSourceUrl") ?: return
            val name = when {
                magnetUri.startsWith("magnet:") -> {
                    val dn = magnetUri.substringAfter("dn=", "").substringBefore("&")
                    Uri.decode(dn).ifBlank { infoHash }
                }
                else -> folderPath.split("/").firstOrNull { it.isNotBlank() } ?: infoHash
            }
            savedTorrentRepo.save(
                TorrentResult(
                    name = name,
                    magnetUri = magnetUri,
                    infoHash = infoHash,
                    sizeBytes = 0L,
                    seeders = 0,
                    leechers = 0,
                    source = "",
                )
            )
        }
    }

    // ── Folder mode (Browser) ─────────────────────────────────────────────────
    // Phase 1: resolve clicked track (cache-first) → start immediately.
    // Phase 2: resolve all other tracks in parallel → stitch into queue.
    //
    // If the controller hasn't connected by Phase 2 (fast on cached playlists),
    // upgrade pendingMediaItem → pendingPlaylist so the full queue is applied
    // once the controller becomes available.

    private fun fetchAndPlayFolder() {
        PlaybackService.currentPlaylistId = null
        val cloudTypeStr = savedStateHandle.get<String>("cloudType") ?: return
        val sourceUrl = savedStateHandle.get<String>("encodedSourceUrl") ?: return
        val folderPath = savedStateHandle.get<String>("encodedFolderPath") ?: return
        val mediaId = savedStateHandle.get<String>("encodedMediaId") ?: return

        _isLoadingPlaylist.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cloudType = CloudType.valueOf(cloudTypeStr)
                val path = CloudPath(sourceId = sourceUrl, relativePath = folderPath, cloudType = cloudType)

                val allItems = listFolder(path).first()

                // Detect images for gallery button (root check is instant — no extra API call)
                val imageFilesInRoot = allItems.filter { it.type == CloudItem.ItemType.FILE && it.name.isImageFile() }
                withContext(Dispatchers.Main) {
                    _folderInfo.value = FolderInfo(cloudTypeStr, sourceUrl, folderPath, imageFilesInRoot.isNotEmpty())
                }
                val folderKey = "$cloudType:$sourceUrl:$folderPath"
                lastScannedFolderKey = folderKey
                // Scan root + all subfolders for images; store items, resolve URLs on-demand in UI
                launch {
                    val images = collectCoverImages(allItems)
                    withContext(Dispatchers.Main) {
                        if (lastScannedFolderKey == folderKey) {
                            _coverImages.value = images
                            if (images.isNotEmpty() && !imageFilesInRoot.any { it.id in images.map { img -> img.id } }) {
                                _folderInfo.value = _folderInfo.value?.copy(hasImages = true)
                            }
                        }
                    }
                }

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

                val clickedIndex = mediaFiles.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
                val clickedItem = mediaFiles[clickedIndex]

                withContext(Dispatchers.Main) {
                    _playerState.value = _playerState.value.copy(title = clickedItem.name)
                }

                // Phase 1: cache-first resolve of clicked track → start immediately
                val clickedMediaItem = resolveFolderItem(clickedItem)
                if (clickedMediaItem == null) {
                    withContext(Dispatchers.Main) {
                        _isLoadingPlaylist.value = false
                        _error.value = "Нет доступных треков: подключитесь к сети или скачайте треки заранее"
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { _isLoadingPlaylist.value = false }

                if (isTorrentStream) {
                    try {
                        waitForTorrentBuffer(clickedItem.id)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            _torrentPreBuffer.value = TorrentPreBufferState.Idle
                            _error.value = e.message ?: "Ошибка буферизации торрента"
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        _torrentPreBuffer.value = TorrentPreBufferState.Idle
                        playMediaItem(clickedMediaItem)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        val c = controller
                        if (c == null) pendingMediaItem = clickedMediaItem else playMediaItem(clickedMediaItem)
                    }
                }

                if (mediaFiles.size == 1) return@launch

                // Phase 2: resolve all other tracks in parallel (cached = no network)
                var beforeItems: List<MediaItem> = emptyList()
                var afterItems: List<MediaItem> = emptyList()
                coroutineScope {
                    val bd = mediaFiles.subList(0, clickedIndex)
                        .map { item -> async { resolveFolderItem(item) } }
                    val ad = mediaFiles.subList(clickedIndex + 1, mediaFiles.size)
                        .map { item -> async { resolveFolderItem(item) } }
                    beforeItems = bd.awaitAll().filterNotNull()
                    afterItems = ad.awaitAll().filterNotNull()
                }

                withContext(Dispatchers.Main) {
                    val c = controller
                    when {
                        c != null -> {
                            // Controller ready and Phase 1 item is playing — stitch in the rest.
                            // Use beforeItems.size + 1 (not c.currentMediaItemIndex) because
                            // MediaController IPC is async and the index may not be updated yet.
                            // Don't gate on c.mediaItemCount > 0: local state may lag behind
                            // the setMediaItem command sent in Phase 1.
                            if (beforeItems.isNotEmpty()) c.addMediaItems(0, beforeItems)
                            if (afterItems.isNotEmpty()) c.addMediaItems(beforeItems.size + 1, afterItems)
                        }
                        else -> {
                            // Controller still connecting (happens when Phase 2 is instant for cached
                            // items). Upgrade single pending item to full playlist so the controller
                            // picks up the complete queue when it connects.
                            pendingMediaItem = null
                            pendingPlaylist = Pair(
                                beforeItems + listOf(clickedMediaItem) + afterItems,
                                beforeItems.size,
                            )
                        }
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

                // startIndex is the visual index from PlaylistDetailScreen, which counts
                // ALL rows including null-cloudItem entries. Walk the full pairs list so
                // the index matches what the user actually tapped.
                val allItems = pairs.map { (_, cloudItem) -> cloudItem }
                val firstItem = allItems.getOrNull(startIndex.coerceIn(0, allItems.lastIndex))
                    ?: cloudItems.first()

                // Position of firstItem inside the non-null cloudItems list for queue building.
                val queueStart = cloudItems.indexOf(firstItem).coerceAtLeast(0)

                // Register as the active playlist so PlaybackService can stop on deletion
                PlaybackService.currentPlaylistId = playlistId

                // If screen is open: observe deletion and surface an error in the UI
                playlistRepo.deletedPlaylistFlow
                    .onEach { deletedId ->
                        if (deletedId == playlistId) {
                            withContext(Dispatchers.Main) {
                                _error.value = "Плейлист был удалён"
                                _playerState.value = PlayerState()
                            }
                        }
                    }
                    .launchIn(viewModelScope)

                // Build mediaId → CloudItem map so onMediaItemTransition can scan per-track folder
                val idToItem = cloudItems.associateBy { it.id }
                withContext(Dispatchers.Main) {
                    mediaIdToCloudItem.clear()
                    mediaIdToCloudItem.putAll(idToItem)
                    _playerState.value = _playerState.value.copy(title = firstItem.name)
                    triggerCoverScan(firstItem.path)
                }

                // Phase 1: cache-first resolve of clicked track → start immediately
                val firstMediaItem = resolvePlaylistItem(firstItem)
                if (firstMediaItem == null) {
                    withContext(Dispatchers.Main) {
                        _isLoadingPlaylist.value = false
                        _error.value = "Нет доступных треков: подключитесь к сети или скачайте треки заранее"
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { _isLoadingPlaylist.value = false }

                if (isTorrentStream) {
                    try {
                        waitForTorrentBuffer(firstItem.id)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            _torrentPreBuffer.value = TorrentPreBufferState.Idle
                            _error.value = e.message ?: "Ошибка буферизации торрента"
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        _torrentPreBuffer.value = TorrentPreBufferState.Idle
                        playMediaItem(firstMediaItem)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        val c = controller
                        if (c == null) pendingMediaItem = firstMediaItem else playMediaItem(firstMediaItem)
                    }
                }

                if (cloudItems.size == 1) return@launch

                // Phase 2: resolve remaining tracks in parallel (cached = no network)
                var beforeItems: List<MediaItem> = emptyList()
                var afterItems: List<MediaItem> = emptyList()
                coroutineScope {
                    val bd = cloudItems.subList(0, queueStart)
                        .map { item -> async { resolvePlaylistItem(item) } }
                    val ad = cloudItems.subList(queueStart + 1, cloudItems.size)
                        .map { item -> async { resolvePlaylistItem(item) } }
                    beforeItems = bd.awaitAll().filterNotNull()
                    afterItems = ad.awaitAll().filterNotNull()
                }

                withContext(Dispatchers.Main) {
                    val c = controller
                    when {
                        c != null -> {
                            // Controller ready and Phase 1 item is playing — stitch in the rest.
                            // Use beforeItems.size + 1 (not c.currentMediaItemIndex) because
                            // MediaController IPC is async and the index may not be updated yet.
                            // Don't gate on c.mediaItemCount > 0: local state may lag behind
                            // the setMediaItem command sent in Phase 1.
                            if (beforeItems.isNotEmpty()) c.addMediaItems(0, beforeItems)
                            if (afterItems.isNotEmpty()) c.addMediaItems(beforeItems.size + 1, afterItems)
                        }
                        else -> {
                            // Controller still connecting (happens when Phase 2 is instant for cached
                            // items). Upgrade single pending item to full playlist so the controller
                            // picks up the complete queue when it connects.
                            pendingMediaItem = null
                            pendingPlaylist = Pair(
                                beforeItems + listOf(firstMediaItem) + afterItems,
                                beforeItems.size,
                            )
                        }
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
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.name)
                    .setExtras(item.path.toExtrasBundle())
                    .build()
            )
            .build()

    /** Placeholder URI — CacheDataSource serves data from SimpleCache by CustomCacheKey. */
    private fun buildOfflineMediaItem(mediaId: String, title: String, pathBundle: Bundle? = null) =
        MediaItem.Builder()
            .setUri("https://offline.cache/$mediaId")
            .setMediaId(mediaId)
            .setCustomCacheKey(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .apply { if (pathBundle != null) setExtras(pathBundle) }
                    .build()
            )
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
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _embeddedArtUri.value = null
            updateState()
            // In playlist mode, re-scan images if the new track's folder differs from the current one
            val itemPath = mediaIdToCloudItem[mediaItem?.mediaId]?.path ?: return
            triggerCoverScan(itemPath)
        }
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
        isBuffering = c.playbackState == Player.STATE_BUFFERING && c.mediaItemCount > 0,
        )
        _embeddedArtUri.value = c.mediaMetadata.artworkUri?.toString()
        // If no images loaded yet (e.g. NowPlaying or any fresh ViewModel), recover the folder
        // path from the MediaItem extras — every item built by this VM carries it.
        if (_coverImages.value.isEmpty()) {
            c.currentMediaItem?.mediaMetadata?.extras?.toCloudPath()
                ?.let { triggerCoverScan(it) }
        }
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

    fun setPlaybackSpeed(speed: Float) {
        viewModelScope.launch { settings.setPlaybackSpeed(speed) }
    }

    /** Resolves a fresh URL for a cloud image item — called from the UI per visible page. */
    suspend fun resolveCoverUrl(item: CloudItem): String? =
        item.thumbnailUrl ?: runCatching {
            withContext(Dispatchers.IO) { getStreamUrl(item) }
        }.getOrNull()

    /**
     * Starts a cover scan for the folder containing [itemPath].
     * Must be called from the main thread. No-op if the same folder was already scanned.
     */
    private fun triggerCoverScan(itemPath: CloudPath) {
        // For torrents, relativePath is already the folder; for cloud providers it's a file path.
        val folderRelPath = if (itemPath.cloudType == CloudType.TORRENT) {
            itemPath.relativePath
        } else {
            itemPath.relativePath.substringBeforeLast('/').takeIf { it.isNotEmpty() } ?: "/"
        }
        val folderKey = "${itemPath.cloudType}:${itemPath.sourceId}:$folderRelPath"
        if (folderKey == lastScannedFolderKey) return
        lastScannedFolderKey = folderKey
        viewModelScope.launch(Dispatchers.IO) {
            val folderPath = CloudPath(
                sourceId = itemPath.sourceId,
                relativePath = folderRelPath,
                cloudType = itemPath.cloudType,
            )
            val images = collectCoverImagesFromPath(folderPath)
            withContext(Dispatchers.Main) {
                if (lastScannedFolderKey == folderKey) {
                    _coverImages.value = images
                    // Set folderInfo so the gallery button becomes visible in playlist/single-file mode.
                    // In folder mode _folderInfo is already set by fetchAndPlayFolder(), don't overwrite.
                    if (_folderInfo.value == null && images.isNotEmpty()) {
                        _folderInfo.value = FolderInfo(
                            cloudType = itemPath.cloudType.name,
                            sourceUrl = itemPath.sourceId,
                            folderPath = folderRelPath,
                            hasImages = true,
                        )
                    } else if (_folderInfo.value != null && images.isNotEmpty()) {
                        _folderInfo.value = _folderInfo.value!!.copy(hasImages = true)
                    }
                }
            }
        }
    }

    /** Fetches a folder listing and collects all image files from it and its subfolders. */
    private suspend fun collectCoverImagesFromPath(folderPath: CloudPath): List<CloudItem> {
        if (folderPath.cloudType == CloudType.TORRENT) {
            return collectTorrentCoverImages(folderPath)
        }
        val items = runCatching { listFolder(folderPath).first() }.getOrDefault(emptyList())
        return collectCoverImages(items)
    }

    /**
     * Scans a torrent folder for image files using the engine directly, bypassing
     * [TorrentCloudProvider.buildFolderItems] which filters to audio-only.
     * Also scans immediate subdirectories (e.g. "Artwork/", "Scans/") to match
     * the behaviour of [collectCoverImages] for cloud providers.
     * Enables download priority for every found image file so the HTTP server
     * can serve them — by default all files start at Priority.IGNORE.
     */
    private fun collectTorrentCoverImages(folderPath: CloudPath): List<CloudItem> {
        val sourceId = folderPath.sourceId
        val infoHash = extractInfoHash(sourceId)
            ?: if (sourceId.startsWith("torrent:")) sourceId.removePrefix("torrent:") else return emptyList()
        val allFiles = torrentEngine.listFiles(infoHash)
        val relPath = if (folderPath.relativePath == "/") "" else folderPath.relativePath

        fun imagesAt(dir: String): List<CloudItem> {
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            return allFiles
                .filter { it.name.isImageFile() }
                .filter { file ->
                    if (prefix.isNotEmpty() && !file.relativePath.startsWith(prefix)) return@filter false
                    val remaining = if (prefix.isEmpty()) file.relativePath
                                    else file.relativePath.removePrefix(prefix)
                    !remaining.contains('/')
                }
                .sortedBy { it.name.lowercase() }
                .map { file ->
                    CloudItem(
                        id = "$infoHash:${file.index}",
                        name = file.name,
                        path = CloudPath(sourceId = sourceId, relativePath = dir, cloudType = CloudType.TORRENT),
                        type = CloudItem.ItemType.FILE,
                        sizeBytes = file.sizeBytes,
                        cacheStatus = CacheStatus.REMOTE,
                    )
                }
        }

        val result = imagesAt(relPath).toMutableList()

        // Also check immediate subdirectories that contain images (e.g. "Album/Artwork/")
        val prefix = if (relPath.isEmpty()) "" else "$relPath/"
        val imageSubdirs = allFiles
            .filter { it.name.isImageFile() }
            .mapNotNull { file ->
                if (prefix.isNotEmpty() && !file.relativePath.startsWith(prefix)) return@mapNotNull null
                val remaining = if (prefix.isEmpty()) file.relativePath
                                else file.relativePath.removePrefix(prefix)
                val slashIdx = remaining.indexOf('/')
                if (slashIdx > 0) {
                    val sub = remaining.substring(0, slashIdx)
                    if (relPath.isEmpty()) sub else "$relPath/$sub"
                } else null
            }
            .distinct()
        for (subDir in imageSubdirs) result += imagesAt(subDir)

        // Enable downloading for every image file found — they start at Priority.IGNORE by
        // default and the HTTP server would otherwise block forever waiting for pieces.
        for (item in result) {
            val parts = item.id.split(":")
            if (parts.size >= 2) {
                val idx = parts[1].toIntOrNull() ?: continue
                torrentEngine.enableFileDownload(infoHash, idx)
            }
        }

        return result
    }

    /** Collects all image files from [rootItems] and one level of subfolders. */
    private suspend fun collectCoverImages(rootItems: List<CloudItem>): List<CloudItem> {
        val images = mutableListOf<CloudItem>()
        images += rootItems.filter { it.type == CloudItem.ItemType.FILE && it.name.isImageFile() }
        for (dir in rootItems.filter { it.type == CloudItem.ItemType.DIRECTORY }) {
            val subItems = runCatching { listFolder(dir.path).first() }.getOrDefault(emptyList())
            images += subItems.filter { it.type == CloudItem.ItemType.FILE && it.name.isImageFile() }
        }
        return images
    }

    // ── Bundle helpers ────────────────────────────────────────────────────────

    private fun CloudPath.toExtrasBundle() = Bundle().apply {
        putString("cloudSourceId", sourceId)
        putString("cloudRelPath", relativePath)
        putString("cloudType", cloudType.name)
    }

    private fun Bundle.toCloudPath(): CloudPath? {
        val sourceId = getString("cloudSourceId") ?: return null
        val relPath = getString("cloudRelPath") ?: return null
        val cloudTypeStr = getString("cloudType") ?: return null
        val cloudType = runCatching { CloudType.valueOf(cloudTypeStr) }.getOrNull() ?: return null
        return CloudPath(sourceId = sourceId, relativePath = relPath, cloudType = cloudType)
    }

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
        val isBuffering: Boolean = false,
    )

    data class FolderInfo(
        val cloudType: String,
        val sourceUrl: String,
        val folderPath: String,
        val hasImages: Boolean,
    )

    sealed class TorrentPreBufferState {
        object Idle : TorrentPreBufferState()
        data class Buffering(val progress: Float) : TorrentPreBufferState()
    }

    companion object {
        /** Number of initial file pieces to download before handing off to ExoPlayer. */
        private const val MIN_INITIAL_PIECES = 5
    }
}
