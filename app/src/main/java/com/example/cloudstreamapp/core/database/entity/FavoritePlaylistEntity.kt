package com.example.cloudstreamapp.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_playlists")
data class FavoritePlaylistEntity(
    @PrimaryKey val id: String,
    val originalPlaylistId: String?,
    val name: String,
    val savedAt: Long,
)

@Entity(
    tableName = "favorite_tracks",
    foreignKeys = [ForeignKey(
        entity = FavoritePlaylistEntity::class,
        parentColumns = ["id"],
        childColumns = ["favoritePlaylistId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("favoritePlaylistId")],
)
data class FavoriteTrackEntity(
    @PrimaryKey val id: String,
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
