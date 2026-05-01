package com.example.cloudstreamapp.data.playlist

import com.example.cloudstreamapp.core.cache.MediaCacheManager
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val metadataDao: MediaMetadataDao,
    private val cacheManager: MediaCacheManager,
) : PlaylistRepositoryPort {

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

    override suspend fun delete(id: String) =
        playlistDao.deleteById(id)

    override suspend fun addItem(item: PlaylistItem) =
        playlistDao.insertItem(item.toEntity())

    override suspend fun removeItem(itemId: String) =
        playlistDao.deleteItem(itemId)

    override suspend fun moveItem(itemId: String, newPosition: Int) =
        playlistDao.updateItemPosition(itemId, newPosition)

    suspend fun saveMediaMetadata(cloudItem: CloudItem) {
        val existing = metadataDao.get(cloudItem.path.sourceId, cloudItem.path.relativePath)
        if (existing == null) {
            metadataDao.insert(cloudItem.toMetadataEntity())
        }
    }

    suspend fun saveMediaAndAddToPlaylist(cloudItem: CloudItem, playlistId: String) {
        val mediaId = cloudItem.id
        val existing = metadataDao.get(cloudItem.path.sourceId, cloudItem.path.relativePath)
        if (existing == null) {
            metadataDao.insert(cloudItem.toMetadataEntity())
        }
        val currentItems = playlistDao.getItemsForPlaylist(playlistId).first()
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
        // Update playlist updatedAt
        playlistDao.getById(playlistId)?.let { playlist ->
            playlistDao.update(playlist.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    fun getItemsWithMetadata(playlistId: String): Flow<List<Pair<PlaylistItem, CloudItem?>>> =
        playlistDao.getItemsForPlaylist(playlistId).map { items ->
            items.map { itemEntity ->
                val meta = metadataDao.getById(itemEntity.mediaId)
                val cloudItem = meta?.toCloudItem()?.let { item ->
                    item.copy(cacheStatus = cacheManager.getCacheStatus(item.id, item.sizeBytes))
                }
                itemEntity.toDomain() to cloudItem
            }
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
