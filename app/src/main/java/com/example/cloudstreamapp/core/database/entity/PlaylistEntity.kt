package com.example.cloudstreamapp.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val coverPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val isSmart: Int = 0,
    val smartRules: String?,
)
