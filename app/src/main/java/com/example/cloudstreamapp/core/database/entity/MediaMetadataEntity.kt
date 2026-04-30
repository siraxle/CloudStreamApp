package com.example.cloudstreamapp.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_metadata")
data class MediaMetadataEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val path: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val mimeType: String?,
    val thumbPath: String?,
    val fetchedAt: Long,
)
