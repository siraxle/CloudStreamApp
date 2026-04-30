package com.example.cloudstreamapp.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey val id: String,
    val mediaId: String,
    val playedAt: Long,
    val durationMs: Long?,
    val positionMs: Long?,
)
