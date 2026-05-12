package com.example.cloudstreamapp.domain.port

import com.example.cloudstreamapp.domain.model.FavoritePlaylist
import kotlinx.coroutines.flow.Flow

interface FavoritePlaylistRepositoryPort {
    fun getAll(): Flow<List<FavoritePlaylist>>
    suspend fun getById(id: String): FavoritePlaylist?
    suspend fun save(favorite: FavoritePlaylist)
    suspend fun delete(id: String)
    suspend fun findByOriginalId(originalPlaylistId: String): FavoritePlaylist?
    suspend fun updateOriginalId(favoriteId: String, newOriginalId: String?)
}
