package com.example.cloudstreamapp.domain.model

data class FavoritePlaylist(
    val id: String,
    val originalPlaylistId: String?,
    val name: String,
    val savedAt: Long,
    val tracks: List<FavoriteTrack> = emptyList(),
)

data class FavoriteTrack(
    val id: String,
    val favoritePlaylistId: String,
    val mediaId: String,
    val name: String,
    val sourceId: String,
    val relativePath: String,
    val cloudType: String,
    val sizeBytes: Long?,
    val mimeType: String?,
    val position: Int,
)
