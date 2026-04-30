package com.example.cloudstreamapp.domain.port

import com.example.cloudstreamapp.domain.model.Playlist
import com.example.cloudstreamapp.domain.model.PlaylistItem
import kotlinx.coroutines.flow.Flow

interface PlaylistRepositoryPort {
    fun getAll(): Flow<List<Playlist>>
    suspend fun getById(id: String): Playlist?
    suspend fun create(playlist: Playlist)
    suspend fun update(playlist: Playlist)
    suspend fun delete(id: String)
    suspend fun addItem(item: PlaylistItem)
    suspend fun removeItem(itemId: String)
    suspend fun moveItem(itemId: String, newPosition: Int)
}
