package com.example.cloudstreamapp.data.playlist

import androidx.room.withTransaction
import com.example.cloudstreamapp.core.cache.MediaCacheManager
import com.example.cloudstreamapp.core.database.AppDatabase
import com.example.cloudstreamapp.core.database.dao.MediaMetadataDao
import com.example.cloudstreamapp.core.database.dao.PlaylistDao
import com.example.cloudstreamapp.core.database.entity.MediaMetadataEntity
import com.example.cloudstreamapp.core.database.entity.PlaylistEntity
import com.example.cloudstreamapp.core.database.entity.PlaylistItemEntity
import com.example.cloudstreamapp.domain.model.CacheStatus
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudType
import com.example.cloudstreamapp.domain.model.Playlist
import com.example.cloudstreamapp.domain.model.PlaylistItem
import com.example.cloudstreamapp.domain.port.PlaylistRepositoryPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val playlistDao: PlaylistDao,
    private val metadataDao: MediaMetadataDao,
    private val cacheManager: MediaCacheManager,
) : PlaylistRepositoryPort {

    // Incremented whenever media_metadata is written so flows re-read fresh sizeBytes
    private val _metadataVersion = MutableStateFlow(0)

    // Emits the id of a playlist the moment it is deleted — PlaybackService observes this to stop
    private val _deletedPlaylistFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val deletedPlaylistFlow: SharedFlow<String> = _deletedPlaylistFlow.asSharedFlow()

    override fun getAll(): Flow<List<Playlist>> =
        playlistDao.getAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getById(id: String): Playlist? {
        val entity = playlistDao.getById(id) ?: return null
        val items = playlistDao.getItemsForPlaylist(id).first()
        return entity.toDomain(items.map { it.toDomain() })
    }

    override suspend fun create(playlist: Playlist) =
        playlistDao.insert(playlist.toEntity())

    override suspend fun update(playlist: Playlist) =
        playlistDao.update(playlist.toEntity())

    override suspend fun delete(id: String) {
        val mediaIdsToClean = mutableListOf<String>()

        // Single transaction: snapshot → cascade delete → check refs → delete orphaned metadata
        database.withTransaction {
            val items = playlistDao.getItemsOnce(id)
            playlistDao.deleteById(id)
            for (item in items) {
                val otherRefs = playlistDao.countOtherReferences(item.mediaId, id)
                if (otherRefs == 0) {
                    metadataDao.deleteById(item.mediaId)
                    mediaIdsToClean.add(item.mediaId)
                }
            }
        }

        // Signal deletion before touching cache so the player can stop reading before we delete
        _deletedPlaylistFlow.tryEmit(id)

        // Remove from ExoPlayer cache outside the DB transaction (best-effort)
        for (mediaId in mediaIdsToClean) {
            cacheManager.removeCachedFile(mediaId)
        }
        if (mediaIdsToClean.isNotEmpty()) {
            _metadataVersion.update { it + 1 }
        }
    }

    override suspend fun addItem(item: PlaylistItem) =
        playlistDao.insertItem(item.toEntity())

    override suspend fun removeItem(itemId: String) =
        playlistDao.deleteItem(itemId)

    /**
     * Removes a single track from its playlist and, if no other playlist references it,
     * also deletes its cached media file and metadata from the DB.
     */
    suspend fun removeItemAndCleanCache(itemId: String) {
        var mediaIdToClean: String? = null

        database.withTransaction {
            val item = playlistDao.getItemById(itemId)
            if (item != null) {
                playlistDao.deleteItem(itemId)
                val otherRefs = playlistDao.countOtherReferences(item.mediaId, item.playlistId)
                if (otherRefs == 0) {
                    metadataDao.deleteById(item.mediaId)
                    mediaIdToClean = item.mediaId
                }
            } else {
                playlistDao.deleteItem(itemId)
            }
        }

        mediaIdToClean?.let { cacheManager.removeCachedFile(it) }
        _metadataVersion.update { it + 1 }
    }

    override suspend fun moveItem(itemId: String, newPosition: Int) =
        playlistDao.updateItemPosition(itemId, newPosition)

    suspend fun findMetadataId(sourceId: String, relativePath: String): String? =
        metadataDao.get(sourceId, relativePath)?.id

    suspend fun saveMediaMetadata(cloudItem: CloudItem) {
        val existing = metadataDao.get(cloudItem.path.sourceId, cloudItem.path.relativePath)
        if (existing == null) {
            metadataDao.insert(cloudItem.toMetadataEntity())
        }
    }

    suspend fun updateSizeBytes(mediaId: String, sizeBytes: Long) {
        metadataDao.updateSizeBytes(mediaId, sizeBytes)
        _metadataVersion.update { it + 1 }
    }

    /**
     * Called after the entire media cache is cleared (e.g. from Settings).
     * Bumps the version so all open flows re-compute CacheStatus, which will
     * now return REMOTE because SimpleCache has no bytes for any key.
     */
    fun onCacheCleared() {
        _metadataVersion.update { it + 1 }
    }

    suspend fun saveMediaAndAddToPlaylist(cloudItem: CloudItem, playlistId: String): Boolean {
        val mediaId = cloudItem.id
        val existing = metadataDao.get(cloudItem.path.sourceId, cloudItem.path.relativePath)
        if (existing == null) {
            metadataDao.insert(cloudItem.toMetadataEntity())
        }
        val currentItems = playlistDao.getItemsForPlaylist(playlistId).first()
        if (currentItems.any { it.mediaId == mediaId }) return false
        val nextPosition = (currentItems.maxOfOrNull { it.position } ?: -1) + 1
        playlistDao.insertItem(
            PlaylistItemEntity(
                id = UUID.randomUUID().toString(),
                playlistId = playlistId,
                mediaId = mediaId,
                position = nextPosition,
                addedAt = System.currentTimeMillis(),
            )
        )
        playlistDao.getById(playlistId)?.let { playlist ->
            playlistDao.update(playlist.copy(updatedAt = System.currentTimeMillis()))
        }
        return true
    }

    // Reacts to both playlist_items changes AND media_metadata updates (_metadataVersion)
    fun getItemsWithMetadata(playlistId: String): Flow<List<Pair<PlaylistItem, CloudItem?>>> =
        combine(
            playlistDao.getItemsForPlaylist(playlistId),
            _metadataVersion,
        ) { items, _ -> items }
            .map { items ->
                items.map { itemEntity ->
                    val meta = metadataDao.getById(itemEntity.mediaId)
                    val cloudItem = meta?.toCloudItem()?.let { item ->
                        item.copy(cacheStatus = cacheManager.getCacheStatus(item.id, item.sizeBytes))
                    }
                    itemEntity.toDomain() to cloudItem
                }
            }

    // Returns Triple(total, cached, downloading) for the playlist list screen.
    // Also reacts to _metadataVersion so counts update after downloads complete.
    fun getItemCacheStats(playlistId: String): Flow<Triple<Int, Int, Int>> =
        combine(
            playlistDao.getItemsForPlaylist(playlistId),
            _metadataVersion,
        ) { items, _ -> items }
            .map { items ->
                var total = 0; var cached = 0; var partial = 0
                for (itemEntity in items) {
                    total++
                    val meta = metadataDao.getById(itemEntity.mediaId)
                    when (cacheManager.getCacheStatus(itemEntity.mediaId, meta?.sizeBytes)) {
                        CacheStatus.CACHED -> cached++
                        CacheStatus.PARTIAL -> partial++
                        else -> {}
                    }
                }
                Triple(total, cached, partial)
            }

    // --- Mappers ---

    private fun PlaylistEntity.toDomain(items: List<PlaylistItem> = emptyList()) = Playlist(
        id = id, name = name, coverPath = coverPath,
        createdAt = createdAt, updatedAt = updatedAt,
        items = items, isSmart = isSmart == 1, smartRules = smartRules,
    )

    private fun Playlist.toEntity() = PlaylistEntity(
        id = id, name = name, coverPath = coverPath,
        createdAt = createdAt, updatedAt = updatedAt,
        isSmart = if (isSmart) 1 else 0, smartRules = smartRules,
    )

    private fun PlaylistItemEntity.toDomain() = PlaylistItem(
        id = id, playlistId = playlistId, mediaId = mediaId,
        position = position, addedAt = addedAt,
    )

    private fun PlaylistItem.toEntity() = PlaylistItemEntity(
        id = id, playlistId = playlistId, mediaId = mediaId,
        position = position, addedAt = addedAt,
    )

    private fun CloudItem.toMetadataEntity() = MediaMetadataEntity(
        id = id,
        sourceId = path.sourceId,
        path = path.relativePath,
        cloudType = path.cloudType.name,
        title = name,
        artist = null, album = null, genre = null,
        durationMs = durationMs, sizeBytes = sizeBytes, mimeType = mimeType,
        thumbPath = thumbnailUrl, fetchedAt = System.currentTimeMillis(),
    )

    private fun MediaMetadataEntity.toCloudItem() = CloudItem(
        id = id,
        name = title ?: path.substringAfterLast('/'),
        path = CloudPath(
            sourceId = sourceId,
            relativePath = path,
            cloudType = runCatching { CloudType.valueOf(cloudType) }.getOrDefault(CloudType.HTTP),
        ),
        type = CloudItem.ItemType.FILE,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        durationMs = durationMs,
        thumbnailUrl = thumbPath,
        cacheStatus = CacheStatus.REMOTE,
    )
}
