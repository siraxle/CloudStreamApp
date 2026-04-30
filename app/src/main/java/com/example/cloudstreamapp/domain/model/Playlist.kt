package com.example.cloudstreamapp.domain.model

data class Playlist(
    val id: String,
    val name: String,
    val coverPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val items: List<PlaylistItem> = emptyList(),
    val isSmart: Boolean = false,
    val smartRules: String? = null,
)

data class PlaylistItem(
    val id: String,
    val playlistId: String,
    val mediaId: String,
    val position: Int,
    val addedAt: Long,
)
