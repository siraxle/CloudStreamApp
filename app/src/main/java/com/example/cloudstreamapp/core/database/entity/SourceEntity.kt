package com.example.cloudstreamapp.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    val url: String,
    val name: String?,
    val provider: String,
    val addedAt: Long,
    val lastSync: Long?,
    val isPinned: Int = 0,
)
