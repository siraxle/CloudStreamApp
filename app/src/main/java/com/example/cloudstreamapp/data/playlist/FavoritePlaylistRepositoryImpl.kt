package com.example.cloudstreamapp.data.playlist

import androidx.room.withTransaction
import com.example.cloudstreamapp.core.database.AppDatabase
import com.example.cloudstreamapp.core.database.dao.FavoritePlaylistDao
import com.example.cloudstreamapp.core.database.dao.FavoritePlaylistWithTracks
import com.example.cloudstreamapp.core.database.dao.MediaMetadataDao
import com.example.cloudstreamapp.core.database.dao.PlaylistDao
import com.example.cloudstreamapp.core.database.entity.FavoritePlaylistEntity
import com.example.cloudstreamapp.core.database.entity.FavoriteTrackEntity
import com.example.cloudstreamapp.core.database.entity.MediaMetadataEntity
import com.example.cloudstreamapp.core.database.entity.PlaylistEntity
import com.example.cloudstreamapp.core.database.entity.PlaylistItemEntity
import com.example.cloudstreamapp.domain.model.FavoritePlaylist
import com.example.cloudstreamapp.domain.model.FavoriteTrack
import com.example.cloudstreamapp.domain.port.FavoritePlaylistRepositoryPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritePlaylistRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val favoriteDao: FavoritePlaylistDao,
    private val playlistDao: PlaylistDao,
    private val metadataDao: MediaMetadataDao,
) : FavoritePlaylistRepositoryPort {

    override fun getAll(): Flow<List<FavoritePlaylist>> =
        favoriteDao.getAllWithTracks().map { list -> list.map { it.toDomain() } }

    override suspend fun getById(id: String): FavoritePlaylist? =
        favoriteDao.getById(id)?.toDomain()

    override suspend fun save(favorite: FavoritePlaylist) {
        favoriteDao.insert(favorite.toEntity())
        if (favorite.tracks.isNotEmpty()) {
            favoriteDao.insertTracks(favorite.tracks.map { it.toEntity() })
        }
    }

    override suspend fun delete(id: String) = favoriteDao.deleteById(id)

    override suspend fun findByOriginalId(originalPlaylistId: String): FavoritePlaylist? {
        val entity = favoriteDao.findByOriginalId(originalPlaylistId) ?: return null
        return favoriteDao.getById(entity.id)?.toDomain()
    }

    override suspend fun updateOriginalId(favoriteId: String, newOriginalId: String?) =
        favoriteDao.updateOriginalId(favoriteId, newOriginalId)

    /**
     * Snapshots the current state of a playlist and saves it as a favorite.
     * Stores only the name, track names, and URLs — no media files.
     * Returns false if the playlist is not found or already in favorites.
     */
    suspend fun snapshotPlaylist(playlistId: String): Boolean {
        if (favoriteDao.findByOriginalId(playlistId) != null) return false
        val playlistEntity = playlistDao.getById(playlistId) ?: return false
        val items = playlistDao.getItemsOnce(playlistId)

        val favoriteId = UUID.randomUUID().toString()
        val tracks = items.mapIndexed { idx, item ->
            val meta = metadataDao.getById(item.mediaId)
            FavoriteTrackEntity(
                id = UUID.randomUUID().toString(),
                favoritePlaylistId = favoriteId,
                mediaId = item.mediaId,
                name = meta?.title ?: item.mediaId,
                sourceId = meta?.sourceId ?: "",
                relativePath = meta?.path ?: item.mediaId,
                cloudType = meta?.cloudType ?: "HTTP",
                sizeBytes = meta?.sizeBytes,
                mimeType = meta?.mimeType,
                position = idx,
            )
        }

        favoriteDao.insert(
            FavoritePlaylistEntity(
                id = favoriteId,
                originalPlaylistId = playlistId,
                name = playlistEntity.name,
                savedAt = System.currentTimeMillis(),
            )
        )
        if (tracks.isNotEmpty()) {
            favoriteDao.insertTracks(tracks)
        }
        return true
    }

    /**
     * Removes a playlist from favorites.
     * Looks up the favorite by the original playlist ID and deletes it.
     * Returns false if not found.
     */
    suspend fun unfavoriteByOriginalId(playlistId: String): Boolean {
        val entity = favoriteDao.findByOriginalId(playlistId) ?: return false
        favoriteDao.deleteById(entity.id)
        return true
    }

    /**
     * Creates a new playlist from a favorited snapshot.
     * Re-inserts track metadata (if absent) and links them to the new playlist.
     * Updates the favorite's originalPlaylistId to point to the new playlist.
     * Returns the new playlist ID, or null if the favorite was not found.
     */
    suspend fun restoreFavorite(favoriteId: String): String? {
        val favoriteWithTracks = favoriteDao.getById(favoriteId) ?: return null

        // If a playlist with the same name and tracks already exists, reuse it
        val existingId = findDuplicateInMainList(favoriteWithTracks)
        if (existingId != null) {
            favoriteDao.updateOriginalId(favoriteId, existingId)
            return existingId
        }

        val newPlaylistId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        database.withTransaction {
            playlistDao.insert(
                PlaylistEntity(
                    id = newPlaylistId,
                    name = favoriteWithTracks.playlist.name,
                    coverPath = null,
                    createdAt = now,
                    updatedAt = now,
                    isSmart = 0,
                    smartRules = null,
                )
            )
            for (track in favoriteWithTracks.tracks.sortedBy { it.position }) {
                if (metadataDao.getById(track.mediaId) == null) {
                    metadataDao.insert(
                        MediaMetadataEntity(
                            id = track.mediaId,
                            sourceId = track.sourceId,
                            path = track.relativePath,
                            cloudType = track.cloudType,
                            title = track.name,
                            artist = null,
                            album = null,
                            genre = null,
                            durationMs = null,
                            sizeBytes = track.sizeBytes,
                            mimeType = track.mimeType,
                            thumbPath = null,
                            fetchedAt = now,
                        )
                    )
                }
                playlistDao.insertItem(
                    PlaylistItemEntity(
                        id = UUID.randomUUID().toString(),
                        playlistId = newPlaylistId,
                        mediaId = track.mediaId,
                        position = track.position,
                        addedAt = now,
                    )
                )
            }
            favoriteDao.updateOriginalId(favoriteId, newPlaylistId)
        }

        return newPlaylistId
    }

    private suspend fun findDuplicateInMainList(favoriteWithTracks: FavoritePlaylistWithTracks): String? {
        val incomingKeys = favoriteWithTracks.tracks
            .map { "${it.sourceId}|${it.relativePath}" }.toSet()
        val allPlaylists = playlistDao.getAll().first()
        for (playlist in allPlaylists) {
            if (playlist.name != favoriteWithTracks.playlist.name) continue
            val items = playlistDao.getItemsOnce(playlist.id)
            val existingKeys = items.mapNotNull { item ->
                metadataDao.getById(item.mediaId)?.let { "${it.sourceId}|${it.path}" }
            }.toSet()
            if (existingKeys == incomingKeys) return playlist.id
        }
        return null
    }

    // --- Mappers ---

    private fun FavoritePlaylistWithTracks.toDomain() = FavoritePlaylist(
        id = playlist.id,
        originalPlaylistId = playlist.originalPlaylistId,
        name = playlist.name,
        savedAt = playlist.savedAt,
        tracks = tracks.sortedBy { it.position }.map { it.toDomain() },
    )

    private fun FavoriteTrackEntity.toDomain() = FavoriteTrack(
        id = id,
        favoritePlaylistId = favoritePlaylistId,
        mediaId = mediaId,
        name = name,
        sourceId = sourceId,
        relativePath = relativePath,
        cloudType = cloudType,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        position = position,
    )

    private fun FavoritePlaylist.toEntity() = FavoritePlaylistEntity(
        id = id,
        originalPlaylistId = originalPlaylistId,
        name = name,
        savedAt = savedAt,
    )

    private fun FavoriteTrack.toEntity() = FavoriteTrackEntity(
        id = id,
        favoritePlaylistId = favoritePlaylistId,
        mediaId = mediaId,
        name = name,
        sourceId = sourceId,
        relativePath = relativePath,
        cloudType = cloudType,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        position = position,
    )
}
