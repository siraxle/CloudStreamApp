package com.example.cloudstreamapp.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "folder_cache",
    foreignKeys = [ForeignKey(
        entity = SourceEntity::class,
        parentColumns = ["id"],
        childColumns = ["sourceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sourceId")],
)
data class FolderCacheEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val path: String,
    val itemsJson: String,
    val cachedAt: Long,
    val etag: String?,
)
